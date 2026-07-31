# 设计文档：ifmix_server 数据层迁移到 TiDB

日期：2026-07-31

## 概述

把 ifmix_server 的数据层从 PostgreSQL 换成 TiDB，schema 从零重建。代码目前只在本地阶段、没有生产数据，因此不做任何兼容性妥协——所有不适合 TiDB 的类型和结构一次改对。

### 目标

1. 分布式、多写、水平扩展，承载大数据量
2. 业务侧只关心业务，不介入基础设施细节
3. 技术限制无法隐藏时（例如强一致读 vs 有界陈旧读），以显式、最小的 API 暴露给业务侧
4. 长远合理性优先于迁移成本

### 选型结论

锁定 TiDB。同类 PG 线协议的分布式 SQL 一并评估后排除：

| 候选 | 排除理由 |
|---|---|
| CockroachDB | BSL 许可；国内基本无生态 |
| YugabyteDB | 国内几乎无人使用，出问题无处求援 |
| Aurora DSQL | 已知限制和坑较多 |

代价是明确的：TiDB 走 MySQL 8.0 协议，PostgreSQL 的 `UUID`、`JSONB`、`TIMESTAMPTZ` 都没有对应类型，`partial index` 到 v8.5.7 才有（TiDB Cloud Starter 当前是 8.5.3）。这些代价在下面逐项处理。

---

## 决策记录

### 1. 主键：UUIDv4 存 `BINARY(16)`，`CLUSTERED`

领域模型继续用 `java.util.UUID`，存储用 `BINARY(16)`，显式声明 `CLUSTERED`，不做字节交换（保留 UUIDv4 的随机高位）。这是 TiDB 官方对 UUID 主键的推荐形式，随机高位天然打散 region，写入无热点。

**ID 由应用侧生成，不用 `AUTO_RANDOM`。** 虽然 `AUTO_RANDOM` 是 8 字节、更省存储、且支持建表即 `PRE_SPLIT_REGIONS`，但它把"ID 的定义权"交给了数据库，代价会渗透进业务代码：

- `AuthService.refresh()` 需要在插入新 token 前就持有它的 ID，以便同一事务里把旧 token 的 `replaced_by` 指向它
- `AntiqueService` 的 `antique/{uuid}.png` 对象存储 key 必须先有 ID 才能上传
- 客户端自带 request id 当主键即可获得幂等，无需额外列 + 唯一索引
- 父子对象（`Todo` + `items`）可以一次组装、一次批量写，而不是"插父 → 读回 ID → 插子"
- 跨集群/导入/离线场景下 UUID 全局唯一，整数会重叠
- 测试夹具可以写死 ID 做断言

省下的 8 字节可以用存储和硬件解决，业务代码的耦合只能用重写解决。

**连带影响**：UUIDv4 无时间序，游标分页不能按 `id` 排序（现有 `CursorQueryInput.sortBy` 默认值 `"_id"` 是 MongoDB 遗留，在 UUIDv4 下本来就没有时间语义）。改用 `(created_at, id)` 复合游标。

UUIDv7 被排除：单调前缀会把写入压在最后一个 region，与"解决多写"直接冲突；要打散就得改成 `NONCLUSTERED` 主键 + `SHARD_ROW_ID_BITS`，每次主键查询多一次回表。

### 2. 时间：`DATETIME(6)` 存 UTC，Kotlin 侧 `java.time.Instant`

`TIMESTAMPTZ` 本身不存储时区，PG 只存 UTC 瞬时，时区仅在渲染时参与。所以换成 `DATETIME(6)` 不丢信息，只是把渲染完全交给应用层——API 层已经是 `Instant` + ISO-8601。

排除的选项：

- **`TIMESTAMP(6)`**：上限 2038-01-19。`subscription.expiry_date` 存终身订阅、`auth_device_secret.expires_at` 存长效凭证都会溢出
- **`BIGINT` epoch**：存储上没有优势（`DATETIME(6)` 和 `BIGINT` 都是 8 字节），却要放弃 TiDB TTL（`TTL = col + INTERVAL n` 需要时间类型列）、时间分区、日期函数和 SQL 控制台的可读性。国内 MySQL 项目里用 `bigint` 的多半是被 `TIMESTAMP` 的 2038 和会话时区转换坑过——痛点是 `TIMESTAMP`，正解是 `DATETIME`
- **`LocalDateTime`**（Java 侧）：它是 MySQL 项目的主流选择，但正确性依赖"所有机器时区一致"这个部署约定，而不是类型本身。写入 pod 是 `Asia/Shanghai`、校验 pod 是 `UTC` 时，同一个字面值被解释成差 8 小时，无异常无日志。夏令时地区还会让 `created_at` 出现回退，直接破坏 `(created_at, id)` 游标的单调性
- **`kotlinx.datetime.Instant` / `kotlin.time.Instant` / 自定义 value class**：需要在 Jackson、Jimmer、Spring 参数绑定、OpenAPI、Redis 多个边界各写适配，而 Apple/Google IAP 响应和 Nimbus JWT 交回来的本来就是 `java.time` 类型

**实现要点**

- Jimmer 全局注册 `ScalarProvider<Instant, LocalDateTime>`，按 `ZoneOffset.UTC` 显式转换，不依赖 JDBC 驱动的时区参数
- 提供 `Clock` bean：`Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))`，产出微秒对齐的 `Instant`。全项目只从它取时间，禁止直接 `Instant.now()`（`Instant` 是纳秒精度，`DATETIME(6)` 是微秒，不截断会让"写入 X 读回 X-137ns"随机破坏测试断言）
- DDL 里不使用 `DEFAULT CURRENT_TIMESTAMP`。`NOW()` 取的是接入的那个 TiDB 节点的本地时钟，多节点间有偏移且受会话时区影响；Jimmer 也拿不到 DB 生成的值

附带确认：TiDB 的 TTL 后台任务要能并行拆分成子任务，前提是主键首列为 `INTEGER` 或二进制串类型（文档明确包含 `BINARY(N)`）。`BINARY(16)` 主键满足；如果当初用 `VARCHAR(36)` 存 UUID 则不满足。

### 3. 软删除：按语义分类，不全表统一

TiDB 到 v8.5.7 / v9.0.0 才有 partial index，且 `WHERE` 只支持基本比较符、`IS NULL`、`IS NOT NULL`、`IN` 与常量比较（未文档化 `AND` 组合），也不能建在表达式索引上。TiDB Cloud Starter 当前是 8.5.3。

**策略：schema 设计成不依赖 partial index 也正确，将来把 partial index 当纯优化增量引入。**

现有的 `deleted_at` 混了三种不同的东西，只有一种是真正的软删除：

| 表 | 处理 | 理由 |
|---|---|---|
| `app_config` | append-only 单表，`UNIQUE(app_id, revision)`，去掉 `deleted_at` 和 `updated_at` | 这是版本化，不是软删除。行不可变，`updated_at` 无意义 |
| `collection_item` / `todo` / `todo_item` | 真删除 | 取消收藏就是删除，重新收藏是重新插入；`UNIQUE(collection_id, scan_record_id)` 自然成立 |
| `subscription` | 去掉 `deleted_at`，永不删除 | 财务数据 |
| `store_notification` | 去掉 `deleted_at`，由 TTL 保留 90 天后自动清理 | webhook 幂等记录只在处理窗口内有价值 |
| `collection` / `scan_record` / `agnes_key` | 保留软删除 | 误删可恢复有价值 / 生成成本高有审计价值 / 吊销留痕 |
| `app_info` | `UNIQUE(slug)` | MySQL 语义下多个 NULL 互不冲突，天然等价于 `WHERE slug IS NOT NULL` 的 partial unique index |

只剩 `collection` 的"每个 (app_id, install_id) 最多一个默认收藏夹"需要条件唯一，用两个 STORED 生成列 + 联合唯一索引实现（见 DDL）。

排除的方案：哨兵值（`deleted_at NOT NULL DEFAULT '1970-01-01'` + `UNIQUE(app_id, deleted_at)`）——同一微秒批量软删多条历史行会撞唯一键，而且把"未删除"编码成假时间戳污染了时间语义。

除了少几个技术列，还有一个 TiDB 特有的理由：软删除意味着每个二级索引都要把 `deleted_at` 纳入考虑，否则索引扫出的行还要回表过滤，而 TiDB 的回表是跨节点 RPC，比 PG 的本地 heap 访问贵得多。

**`app_config` append-only 的两个连带后果**

1. 写新版本需要"读 `max(revision)` + 1"，并发写会算出同一个 revision，其中一个撞 `app_config_app_rev_uq`。这是正确的乐观并发行为，业务侧捕获重复键重试即可（app_config 是后台管理写，频率极低，不引入悲观锁）
2. `findByBundleId` / `findByAndroidPackage` 要重写。现在依赖 `@LogicalDeleted` 自动过滤所以 `fetchOneOrNull()` 能用；append-only 后同一个 bundle_id 会命中该 app 的所有历史版本。改法：先按 `(apple_bundle_id, app_id)` 索引拿到 app_id，再按 app_id 取最新 revision，最后校验取回的行 `apple_bundle_id` 仍等于入参（防止 bundle_id 曾被修改、历史行残留旧绑定）

### 4. 读一致性：显式 `ReadMode` 参数

TiDB 只有一个逻辑入口，没有 writer/reader 两个 DSN，读一致性是会话级的。现有的 `ReadWriteRoutingDataSource`（双 DataSource + `@Transactional(readOnly)` 路由）整套删除。

TiDB 提供三档：

| 档位 | 机制 | 语义 |
|---|---|---|
| 强一致读（默认） | 走 leader | 读己之写，线性一致 |
| Follower Read | `tidb_replica_read = closest-adaptive` | **仍然强一致**（走 read index），只降延迟 |
| Stale Read | `tidb_read_staleness = -N` | 有界陈旧，可能读不到刚写的数据，延迟最低 |

Follower Read 不牺牲一致性，**全局默认开启**，业务侧无需知道。只有第三档需要业务侧知情：

```kotlin
sealed interface ReadMode {
    data object Strong : ReadMode
    data class BoundedStale(val seconds: Int) : ReadMode
}

repo.findByCursor(query, read = ReadMode.BoundedStale(5))   // 默认 Strong
```

选显式参数而不是注解 + AOP，两个理由：

1. 陈旧读是**单次查询**的属性，不是方法的属性。同一个 service 方法里可能既要强一致读订阅状态、又要陈旧读列表，注解的方法级粒度表达不了
2. AOP + 会话变量在 Hikari 连接复用下有污染风险：归还连接前漏一次重置就污染后续所有请求，这类 bug 极难定位。参数式可以在取连接时设置、`finally` 里无条件重置，作用域封闭

### 5. JSON 列：从 9 个降到 1 个

TiDB 有 JSON 类型，但不能直接建索引（需要生成列或 `CAST(... AS ... ARRAY)` 多值索引），没有 GIN，没有 `@>` / `?` / `#>` 操作符，更新时整列重写。能少用就少用。

逐列审计结果：

| 列 | 代码里的实际情况 | 处理 |
|---|---|---|
| `app_config.apple_config` | 5 个固定标量字段，含 `privateKey` | 拆成 5 个扁平列 |
| `app_config.google_config` | `serviceAccount` + `clientIds{ios,android,web}`，结构固定 | 拆成 4 个扁平列 |
| `app_config.iap_config` | `env` 是标量；`productTierMap` 是动态 map | `env` 提成列；`productTierMap` **保留为唯一的 JSON 列** |
| `auth_identity.profile` | `AuthService:75,100` 恒为 `null` | 删列 |
| `auth_identity.metadata` | `AuthService:76,101` 恒为 `null` | 删列 |
| `app_user.metadata` | `AppUserRepository:40` 恒为 `null` | 删列 |
| `auth_provider_identity.provider_metadata` | `AuthService:119` 恒为 `null` | 删列 |
| `auth_provider_identity.user_metadata` | 只存 `{name, picture}`，`name` 已提到 `display_name` 列 | `picture` → `avatar_url` 列，删 JSON 列 |
| `subscription.raw_response` | `IapService:118` 存的 5 个字段全部已是独立列 | 删列（100% 冗余） |
| `store_notification.raw_payload` | 实体声明是 `String?`，DDL 是 `JSONB` | 改 `LONGTEXT` |

两个具体发现：

**`store_notification.raw_payload` 现有 DDL 是错的。** Apple 的服务器通知是 JWS（点分三段字符串），Google 的是 base64 编码的 Pub/Sub 消息，两者都不是 JSON 对象。实体已经声明成 `String?`，只有 DDL 写了 `JSONB`，在 PG 下靠隐式 cast 存成了 JSON 字符串标量。换 TiDB 正好改对。

**四个恒 `null` 的 JSON 列是典型的预留字段。** 从 MongoDB 时代带过来，从未写入。留着的代价不是磁盘，而是它们迟早变成没有 schema 约束的垃圾桶。TiDB 上 `ADD COLUMN` 是秒级元数据变更，真需要时加列比清理垃圾桶便宜。按 YAGNI 删除。

`productTierMap` 保留 JSON 是正当用法：键集合真正动态（product id → tier），永远随整份 config 一起读取、从不单独查询（config 本身已有内存缓存），不需要索引。考虑过拆成 `app_iap_product_tier` 表，否掉——拆表只增加一次 join 和一张表的维护，没有换来任何查询能力。

连带：`AppConfigRepo.toFlat()` 那段手写摊平映射整个删除，DTO 直接映射列。

### 6. 单集群，删除多集群路由

`ClusterProperties` / `ClusterRegistry` / `ReadWriteRoutingDataSource` / `ClusterInitializer` 全部删除，换成单 DSN 的 `TiDbProperties` + `SqlClientConfig`。

现状是个半成品：上一版设计文档写了 `appId → cluster` 的路由意图，但 `ClusterProperties` 实际只有 `writer` + `reader` 两个 DSN。所以删掉的是一个未完成的抽象。

TiDB 的存在意义就是一个集群水平扩展到底，在它上面再叠一层应用内分库路由等于把刚买来的东西拆掉。那几个"想用多集群解决"的问题都有原生方案：

| 需求 | TiDB 原生方案 |
|---|---|
| 数据必须留在特定地域 | Placement Rules in SQL：按表/分区把 leader、follower 钉到指定 region/AZ |
| 大租户不影响小租户 | Resource Control 资源组，按租户配 RU 上限 |
| 读延迟 | Follower Read `closest-adaptive` |

而应用内多集群的代价全在业务侧：同进程持多个连接池、跨集群无事务无 join、跨租户唯一约束失效（`agnes_key.key`、`app_info.slug`）、缓存要分区、监控要按集群拆维度。

真需要物理隔离（跨境部署、合同级数据隔离）时走**部署级隔离**：两套完全独立的服务实例，各自一个 TiDB 集群，在 DNS / 流量层分流。每个进程内仍是单集群、代码零分叉，隔离性反而更强。

### 7. 迁移执行：独立作业，启动只 validate

TiDB 的 DDL 是 online 的（不阻塞读写）但**不在事务里**，每条 DDL 隐式提交，Flyway 无法回滚中途失败的迁移文件。现有的启动时 `ClusterInitializer` 跑 migrate 在多实例下会出现"某实例跑到一半失败、留下半成品 schema、其他实例继续启动"。

改成：

- 迁移由 Gradle task（本地）/ CI 步骤 / K8s Job（生产）独立触发，是显式、可审计、可回看日志的操作
- 应用启动只调 `flyway.validate()`，版本不一致以非零码退出，不自动改 schema
- 好处：schema 变更与应用发布解耦，可以先迁移再滚动发布（这才是 online DDL 的正确用法）；失败时只有一个作业挂掉，不是 N 个 pod 反复重启

**依赖替换**

| 移除 | 加入 |
|---|---|
| `org.flywaydb:flyway-database-postgresql` | `org.flywaydb:flyway-database-tidb` |
| `org.postgresql:postgresql` | `com.mysql:mysql-connector-j` |
| `org.testcontainers:postgresql` | （改用 `GenericContainer`） |
| `com.h2database:h2` | — |

**本地与测试环境都跑真 TiDB，不用 MySQL 替身。** MySQL 任何版本都不支持 partial index（只有 8.0.13+ 的表达式索引），而且 `CLUSTERED`、`SPLIT TABLE`、`AS OF TIMESTAMP`、`tidb_read_staleness`、`GLOBAL` 索引选项、TTL 表这些语法 MySQL 都解析不了，Flyway 迁移会直接失败。更危险的是那些能跑过但行为不同的：MySQL 强制 FK、有 gap lock、RR 语义与 TiDB 的快照隔离不同、写冲突错误码不同、`utf8mb4` 默认 collation 不同（TiDB 是 `utf8mb4_bin`，MySQL 8 是 `utf8mb4_0900_ai_ci`，直接影响 email/slug 的唯一性判定）。

本地用 `tiup playground`（macOS 原生支持，单命令起单机集群），测试用 Testcontainers `GenericContainer`。

### 8. DAO 层：薄基类 + Jimmer 全局机制

现有代码的三个真实缺陷，方案必须解决：

1. `AppScopedFilter.kt` 只有一个 `RequestContextHolder`，注释写着 "tenant isolation is handled manually in repositories"——租户条件全靠各 repo 手写 `where(table.appId eq appId)`，漏一处就是跨租户数据泄露
2. `BaseCrudService.findByCursor` 是假实现，直接 `repo.findAll()` 包成 `Page(all, null, false)`
3. `CollectionItemRepository.insertIfAbsent` 是"先查再插"，并发下会双写

排除的替代方案：

- **去掉基类继承改组合**：现有继承结构很薄（就是泛型 CRUD），痛感不明显，改动面却覆盖所有 repo。YAGNI
- **不做全局过滤器、租户条件全部显式**：这就是现状，而它已经产生了隐患。跨租户泄露是最不能靠纪律防的一类问题

全局过滤器的真实代价要记录在案：它依赖 `ThreadLocal`。项目开了 `spring.threads.virtual.enabled: true`，虚拟线程各有独立 `ThreadLocal`，正常请求路径没问题；但用 `CompletableFuture` 或协程把工作切到别的线程时 appId 不会自动传递。规避手段是过滤器拿不到 `RequestContext` 时直接抛异常（见下）。

---

## 架构

### infra 层的删与建

**删除**（`infra/jimmer/` 整包）

`ClusterProperties`、`ClusterRegistry`、`ReadWriteRoutingDataSource`、`ClusterInitializer`。

`infra/jimmer/AppScopedFilter.kt` 当前只装着一个 `RequestContextHolder`（过滤器本身是 TODO）。整包删除时 `RequestContextHolder` 移到 `infra/http/`，和 `RequestContext` 同包——它是 HTTP 请求作用域的概念，放在数据层包里是错位。真正的过滤器在 `infra/db/tidb/AppScopedFilter.kt` 新写。

测试文件一并删除：`ClusterRegistryTest`、`ReadWriteRoutingDataSourceTest`，以及 MongoDB 时代的遗留 `MongoClusterResolverTest`、`AbstractMongoTest`、`MongoSerializationTest`、`TestDocument`。

**新建**，分两层

`infra/db/` —— 与具体 ORM 无关的契约

| 文件 | 职责 |
|---|---|
| `ReadMode.kt` | `Strong` / `BoundedStale(seconds)` |
| `Cursor.kt` | `(createdAt, id)` 复合游标编解码，对外是不透明 base64 串 |
| `CursorQueryInput.kt`（已有） | 删除 `sortBy` 的 `"_id"` 默认值 |
| `Page.kt` / `Ownership.kt`（已有） | 不变 |

`infra/db/tidb/` —— TiDB + Jimmer 实现

| 文件 | 职责 |
|---|---|
| `TiDbProperties.kt` | 单 DSN + 连接池参数 |
| `TiDbDialect.kt` | `class TiDbDialect : MySqlDialect() { override fun isForeignKeySupported() = false }` |
| `SqlClientConfig.kt` | Hikari `DataSource` + `PlatformTransactionManager` + `KSqlClient`（装配 dialect、ScalarProvider、全局 Filter、ConnectionManager） |
| `AppScopedFilter.kt` | `KFilter<AppScopedProps>` 实现 + `SystemScope` 绕过开关 |
| `ReadModeContext.kt` | `ThreadLocal<ReadMode>` + 在 ConnectionManager 里设置/重置会话变量 |
| `TxRunner.kt` | `retryOnConflict` / `retryOnDuplicateKey`（填掉现在空的 `TransactionConfig.kt`） |
| `FlywayValidateRunner.kt` | 启动时只 `validate()`，版本不符退出 |
| `ClockConfig.kt` | 微秒精度 `Clock` bean |

### 为什么自定义 Dialect

Jimmer 0.11.5 自带 `TiDBDialect`，但它继承 `MySqlStyleDialect`，`isUpsertSupported()` 返回 `false`——用它会让 `save()` 退化成"先查再写"（非原子，正是 `insertIfAbsent` 现在的毛病）。继承 `MySqlDialect` 才能拿到 `INSERT ... ON DUPLICATE KEY UPDATE`，同时覆写 `isForeignKeySupported()` 为 `false`。

### UUID 映射不需要自定义代码

Jimmer 的 `ScalarProviderManager` 看到属性上的 `@Column(sqlType = "binary")` 会自动选内置的 `ScalarProvider.uuidByByteArray()`。它的实现是 `putLong(mostSignificantBits)` 后 `putLong(leastSignificantBits)`——大端、不做字节交换，正是 TiDB 文档建议的形式（等价于 `UUID_TO_BIN(uuid)` 不带 `swap_flag`）。

---

## Schema 约定

**字符集与排序规则**：所有表 `CHARACTER SET utf8mb4 COLLATE utf8mb4_bin`。

用 `_bin` 而非 `_0900_ai_ci`：邮箱和 slug 的归一化已在应用层完成（`EmailNormalize`），DB 只需做确定性的二进制比较。TiDB 的大小写不敏感 collation 行为受集群级 `new_collations_enabled_on_first_bootstrap` 影响，显式指定 `_bin` 就与集群初始化方式无关。

**类型映射**

| PG 现状 | TiDB | 说明 |
|---|---|---|
| `UUID` | `BINARY(16)` | 实体标 `@Column(sqlType = "binary")` |
| `TIMESTAMPTZ` | `DATETIME(6)` | UTC，无 `DEFAULT` |
| `JSONB` | `JSON` | 只剩 `app_config.product_tier_map` |
| `BOOLEAN` | `BOOLEAN` | 即 `TINYINT(1)` |
| `TEXT` | `TEXT` / `LONGTEXT` | 按预期长度选 |
| `VARCHAR(n)` | `VARCHAR(n)` | 不变 |

**禁用清单**

- `AUTO_INCREMENT`（写热点）
- `FOREIGN KEY`（分布式下的跨节点校验开销；Jimmer dialect 已关闭 FK 支持）
- MySQL `ENUM` / `SET`（加枚举值要 DDL，改用 `VARCHAR(32)` + Kotlin enum）
- `DEFAULT CURRENT_TIMESTAMP`（见决策 2）
- 降序索引（TiDB 语法接受但忽略）

**索引规则**

- 租户表的列表查询统一用 `(app_id, created_at, id)`，直接服务 `(created_at, id)` 游标分页
- 现有 DDL 里的 `(app_id, id DESC)` 全部删除：UUIDv4 下按 id 排序无时间语义，降序修饰符还会被忽略
- 唯一索引尽量少：TiDB 每个唯一索引在写入时都要额外做一次冲突校验，跨 region 时是一次 RPC
- 索引键长度上限默认 3072 字节。`VARCHAR(1024)` 在 utf8mb4 下是 4096 字节，超限，需用前缀索引

**分区与 TTL**

- `subscription` **不分区、不 TTL**，永久保留。它的查询是按 `subscription_pxid` / `original_transaction_id` 点查，不含时间条件，按 `created_at` 分区会导致每次查询扫所有分区；而且分区表的唯一索引必须包含分区键（除非用 v8.3+ 的 `GLOBAL` 索引）。行数量级是"用户数"，可控
- `store_notification` 用 `TTL = created_at + INTERVAL 90 DAY`，不分区。一行 DDL 替代定时清理任务
- `auth_device_secret` / `app_refresh_token` 用 `TTL = expires_at + INTERVAL 30 DAY`。这两张表会无限增长，而过期 30 天后的记录没有价值

**预分裂**（独立运维脚本，不进 Flyway）

写入量大的表用 `SPLIT TABLE <t> BETWEEN (x'00...') AND (x'ff...') REGIONS 16`。候选：`scan_record`、`collection_item`、`feedback`、`store_notification`。

不放进 Flyway 迁移的理由：unistore 单节点模式下没有真实 region，这条语句在测试环境无意义甚至报错；而且分几个 region 取决于集群规模和预期写入量，属于运维调优，不该和表结构版本绑定。

---

## 完整 DDL

### V1__auth.sql

```sql
CREATE TABLE auth_tenant (
  id                  BINARY(16)   NOT NULL,
  jwt_private_key_pem TEXT         NULL,
  jwt_issuer          VARCHAR(255) NULL,
  created_at          DATETIME(6)  NOT NULL,
  updated_at          DATETIME(6)  NOT NULL,
  PRIMARY KEY (id) CLUSTERED
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_identity (
  id             BINARY(16)   NOT NULL,
  auth_tenant_id BINARY(16)   NOT NULL,
  raw_email      VARCHAR(255) NULL,
  email          VARCHAR(255) NULL,
  raw_phone      VARCHAR(50)  NULL,
  phone          VARCHAR(50)  NULL,
  contact_email  VARCHAR(255) NULL,
  display_name   VARCHAR(255) NULL,
  password_hash  VARCHAR(255) NULL,
  created_at     DATETIME(6)  NOT NULL,
  updated_at     DATETIME(6)  NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  KEY auth_identity_tenant_email_idx (auth_tenant_id, email),
  KEY auth_identity_tenant_phone_idx (auth_tenant_id, phone)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_provider_identity (
  id                  BINARY(16)    NOT NULL,
  auth_tenant_id      BINARY(16)    NOT NULL,
  auth_identity_id    BINARY(16)    NOT NULL,
  provider            VARCHAR(32)   NOT NULL,
  provider_account_id VARCHAR(255)  NOT NULL,
  email               VARCHAR(255)  NULL,
  email_verified      BOOLEAN       NOT NULL DEFAULT FALSE,
  phone               VARCHAR(50)   NULL,
  avatar_url          VARCHAR(1024) NULL,
  login_ip            VARCHAR(45)   NULL,
  login_install_id    BINARY(16)    NULL,
  login_app_id        BINARY(16)    NULL,
  created_at          DATETIME(6)   NOT NULL,
  updated_at          DATETIME(6)   NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY auth_provider_uq (auth_tenant_id, provider, provider_account_id),
  KEY auth_provider_identity_idx (auth_tenant_id, auth_identity_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE app_user (
  id               BINARY(16)  NOT NULL,
  app_id           BINARY(16)  NOT NULL,
  auth_identity_id BINARY(16)  NOT NULL,
  created_at       DATETIME(6) NOT NULL,
  updated_at       DATETIME(6) NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY app_user_uq (app_id, auth_identity_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_device_secret (
  id               BINARY(16)   NOT NULL,
  auth_tenant_id   BINARY(16)   NOT NULL,
  auth_identity_id BINARY(16)   NOT NULL,
  secret_hash      VARCHAR(255) NOT NULL,
  login_install_id BINARY(16)   NULL,
  expires_at       DATETIME(6)  NULL,
  revoked_at       DATETIME(6)  NULL,
  last_used_at     DATETIME(6)  NULL,
  created_at       DATETIME(6)  NOT NULL,
  updated_at       DATETIME(6)  NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY device_secret_uq (auth_tenant_id, secret_hash),
  KEY device_secret_identity_idx (auth_tenant_id, auth_identity_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  TTL = `expires_at` + INTERVAL 30 DAY;

CREATE TABLE app_refresh_token (
  id               BINARY(16)   NOT NULL,
  app_id           BINARY(16)   NOT NULL,
  app_user_id      BINARY(16)   NOT NULL,
  device_secret_id BINARY(16)   NULL,
  token_hash       VARCHAR(255) NOT NULL,
  login_install_id BINARY(16)   NULL,
  expires_at       DATETIME(6)  NULL,
  revoked_at       DATETIME(6)  NULL,
  replaced_by      BINARY(16)   NULL,
  created_at       DATETIME(6)  NOT NULL,
  updated_at       DATETIME(6)  NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY refresh_token_uq (app_id, token_hash),
  KEY refresh_appuser_idx (app_id, app_user_id),
  KEY refresh_device_idx (device_secret_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  TTL = `expires_at` + INTERVAL 30 DAY;
```

删除的索引：原 `auth_identity_tenant_idx (auth_tenant_id, id)`。UUID 下按 id 排序无序，且点查走主键，这个组合没有查询用途。

### V2__app_config.sql

```sql
CREATE TABLE app_info (
  id          BINARY(16)   NOT NULL,
  name        VARCHAR(255) NULL,
  description TEXT         NULL,
  slug        VARCHAR(255) NULL,
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY app_info_slug_uq (slug)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE app_config (
  id                       BINARY(16)   NOT NULL,
  app_id                   BINARY(16)   NOT NULL,
  revision                 INT          NOT NULL,
  auth_tenant_id           BINARY(16)   NULL,
  apple_bundle_id          VARCHAR(255) NULL,
  android_package_name     VARCHAR(255) NULL,
  apple_app_apple_id       VARCHAR(64)  NULL,
  apple_issuer_id          VARCHAR(64)  NULL,
  apple_key_id             VARCHAR(64)  NULL,
  apple_private_key        TEXT         NULL,
  apple_services_id        VARCHAR(255) NULL,
  google_service_account   TEXT         NULL,
  google_client_id_ios     VARCHAR(255) NULL,
  google_client_id_android VARCHAR(255) NULL,
  google_client_id_web     VARCHAR(255) NULL,
  iap_env                  VARCHAR(32)  NULL,
  product_tier_map         JSON         NULL,
  created_at               DATETIME(6)  NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY app_config_app_rev_uq (app_id, revision),
  KEY app_config_bundle_idx (apple_bundle_id, app_id),
  KEY app_config_package_idx (android_package_name, app_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

`app_config` 无 `deleted_at`、无 `updated_at`（append-only，行不可变）。当前版本 = `WHERE app_id = ? ORDER BY revision DESC LIMIT 1`。

### V3__antique.sql

```sql
CREATE TABLE scan_record (
  id          BINARY(16)   NOT NULL,
  app_id      BINARY(16)   NOT NULL,
  scan_id     VARCHAR(255) NULL,
  image_url   TEXT         NULL,
  result_json TEXT         NULL,
  status      VARCHAR(32)  NULL,
  tier        VARCHAR(32)  NULL,
  client_ip   VARCHAR(45)  NULL,
  related_id  VARCHAR(255) NULL,
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  deleted_at  DATETIME(6)  NULL,
  PRIMARY KEY (id) CLUSTERED,
  KEY scan_record_app_list_idx (app_id, created_at, id),
  KEY scan_record_scan_id_idx (scan_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

### V4__collection.sql

```sql
CREATE TABLE collection (
  id         BINARY(16)  NOT NULL,
  app_id     BINARY(16)  NOT NULL,
  install_id BINARY(16)  NULL,
  user_id    BINARY(16)  NULL,
  is_default BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  default_app_id     BINARY(16) GENERATED ALWAYS AS
      (IF(is_default AND deleted_at IS NULL, app_id, NULL)) STORED,
  default_install_id BINARY(16) GENERATED ALWAYS AS
      (IF(is_default AND deleted_at IS NULL, install_id, NULL)) STORED,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY collection_default_uq (default_app_id, default_install_id),
  KEY collection_app_list_idx (app_id, created_at, id),
  KEY collection_install_idx (app_id, install_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE collection_item (
  id             BINARY(16)  NOT NULL,
  app_id         BINARY(16)  NOT NULL,
  collection_id  BINARY(16)  NOT NULL,
  scan_record_id BINARY(16)  NOT NULL,
  created_at     DATETIME(6) NOT NULL,
  updated_at     DATETIME(6) NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY collection_item_scan_uq (collection_id, scan_record_id),
  KEY collection_item_list_idx (app_id, collection_id, created_at, id),
  KEY collection_item_scan_idx (app_id, scan_record_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

`collection_default_uq` 用两个 STORED 生成列替代 PG 的 `WHERE is_default = TRUE AND deleted_at IS NULL` partial unique index：条件不满足时两列都是 `NULL`，MySQL 语义下含 `NULL` 的唯一索引条目不参与唯一性判定，效果等价。

### V5__iap.sql

```sql
CREATE TABLE subscription (
  id                      BINARY(16)    NOT NULL,
  app_id                  BINARY(16)    NOT NULL,
  subscription_pxid       VARCHAR(255)  NULL,
  original_transaction_id VARCHAR(255)  NULL,
  product_id              VARCHAR(255)  NULL,
  platform                VARCHAR(32)   NULL,
  active                  BOOLEAN       NOT NULL DEFAULT FALSE,
  sub_status              VARCHAR(32)   NULL,
  expiry_date             DATETIME(6)   NULL,
  purchase_token          VARCHAR(1024) NULL,
  created_at              DATETIME(6)   NOT NULL,
  updated_at              DATETIME(6)   NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  KEY subscription_app_list_idx (app_id, created_at, id),
  KEY subscription_pxid_idx (subscription_pxid, active),
  KEY subscription_original_txn_idx (original_transaction_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE store_notification (
  id                BINARY(16)    NOT NULL,
  app_id            BINARY(16)    NOT NULL,
  platform          VARCHAR(32)   NULL,
  subscription_pxid VARCHAR(255)  NULL,
  purchase_token    VARCHAR(1024) NULL,
  notification_type VARCHAR(64)   NULL,
  raw_payload       LONGTEXT      NULL,
  processed         BOOLEAN       NOT NULL DEFAULT FALSE,
  processed_at      DATETIME(6)   NULL,
  created_at        DATETIME(6)   NOT NULL,
  updated_at        DATETIME(6)   NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  KEY store_notif_sub_idx (platform, subscription_pxid, processed_at),
  KEY store_notif_token_idx (platform, purchase_token(255))
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  TTL = `created_at` + INTERVAL 90 DAY;
```

`original_transaction_id` 和 `purchase_token` 从 `TEXT` 改成 `VARCHAR`：`TEXT` 建索引必须指定前缀长度。`store_notif_token_idx` 仍用前缀索引 `purchase_token(255)`，因为 `VARCHAR(1024)` 在 utf8mb4 下是 4096 字节，超过 3072 字节的索引键上限；255 字符约 1020 字节，足够区分。

### V6__agnes_key.sql

```sql
CREATE TABLE agnes_key (
  id                BINARY(16)   NOT NULL,
  app_id            BINARY(16)   NOT NULL,
  `key`             VARCHAR(512) NOT NULL,
  email             VARCHAR(255) NULL,
  type              VARCHAR(32)  NULL,
  rate_limit        BIGINT       NOT NULL DEFAULT -1,
  window_sec        BIGINT       NOT NULL DEFAULT 86400,
  models            TEXT         NULL,
  unavailable_until DATETIME(6)  NULL,
  created_at        DATETIME(6)  NOT NULL,
  updated_at        DATETIME(6)  NOT NULL,
  deleted_at        DATETIME(6)  NULL,
  PRIMARY KEY (id) CLUSTERED,
  UNIQUE KEY agnes_key_uq (`key`),
  KEY agnes_key_app_idx (app_id, unavailable_until)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

### V7__todo_feedback.sql

```sql
CREATE TABLE feedback (
  id             BINARY(16)    NOT NULL,
  app_id         BINARY(16)    NOT NULL,
  install_id     BINARY(16)    NOT NULL,
  user_id        BINARY(16)    NULL,
  scan_record_id BINARY(16)    NULL,
  category       VARCHAR(32)   NOT NULL,
  comment        VARCHAR(1000) NULL,
  created_at     DATETIME(6)   NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  KEY feedback_app_list_idx (app_id, created_at, id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE todo (
  id         BINARY(16)   NOT NULL,
  app_id     BINARY(16)   NOT NULL,
  title      VARCHAR(255) NOT NULL,
  done       BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at DATETIME(6)  NOT NULL,
  updated_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  KEY todo_app_list_idx (app_id, created_at, id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE todo_item (
  id         BINARY(16)    NOT NULL,
  todo_id    BINARY(16)    NOT NULL,
  app_id     BINARY(16)    NOT NULL,
  content    VARCHAR(1000) NOT NULL,
  done       BOOLEAN       NOT NULL DEFAULT FALSE,
  created_at DATETIME(6)   NOT NULL,
  updated_at DATETIME(6)   NOT NULL,
  PRIMARY KEY (id) CLUSTERED,
  KEY todo_item_todo_idx (app_id, todo_id, created_at, id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

`install_id` / `user_id` / `login_app_id` 全部统一为 `BINARY(16)`（原来 `collection` 用 `VARCHAR(255)`、`feedback` 用 `UUID`，同一概念两种类型）。代价是 `x-install-id` 请求头必须是合法 UUID，`HeaderValidationInterceptor` 增加格式校验，非法返回 400。

---

## 实体与映射层

### `RequestContext` 类型收紧

```kotlin
data class RequestContext(
    val appId: UUID,              // 原 String
    val installId: UUID? = null,  // 原 String
    val userId: UUID? = null,     // 原 String
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val clientIp: String? = null,
    // readFromReplica 删除 —— 被 ReadMode 参数取代
)
```

这一改清掉散落各处的 `UUID.fromString(ctx.appId)`（`AuthService:41`、`IapService`、多个 repo），以及 `AppConfigRepo.getByAppId` 里的 `try { UUID.fromString(...) } catch (_: Exception) { null }`——把格式非法当"找不到配置"处理会掩盖真实错误。格式校验上移到 HTTP 边界后，下游拿到的一定是合法 UUID。

### 实体改动

| 项 | 改动 |
|---|---|
| 所有 `UUID` 属性 | 加 `@Column(sqlType = "binary")` |
| `AppScopedProps` | 加 `@MappedSuperclass`（现在是普通接口，KSP 不生成 props，全局过滤器挂不上去） |
| `Collection` / `ScanRecord` / `AgnesKey` | 保留 `@LogicalDeleted("now")` |
| `Todo` / `TodoItem` / `CollectionItem` | 删除 `deletedAt` 属性 |
| `AppConfig` | 删除 `deletedAt` + `updatedAt`；三个 `@Serialized` 属性拆成 14 个扁平属性；`AppleConfigValue` / `GoogleConfigValue` / `GoogleClientIdsValue` / `IapConfigValue` 四个 data class 删除；只剩 `productTierMap: Map<String, String>` 保留 `@Serialized` |
| `Subscription` / `StoreNotification` | 删除 `deletedAt`；`Subscription.rawResponse` 删除 |
| `AuthIdentity` | 删除 `profile` / `metadata` |
| `AuthProviderIdentity` | 删除 `userMetadata` / `providerMetadata`；新增 `avatarUrl` |
| `AppUser` | 删除 `metadata` |
| `CollectionItem` | `collectionId` + `scanRecordId` 标 `@Key` |

`AppConfigRepo.toFlat()` 整个方法删除。

### 关联与外键

`TiDbDialect.isForeignKeySupported() = false` 之后，Jimmer 全局把所有 `@ManyToOne` / `@OneToMany` 当 fake FK 处理，不需要逐个标 `ForeignKeyType.FAKE`。

**行为差异**：删除父对象时 Jimmer 不会级联删子对象（真 FK 下它依赖数据库的 `ON DELETE`）。涉及 `Todo` → `TodoItem`、`Collection` → `CollectionItem`，删除父对象时必须在应用层显式删子对象，放在同一事务内。

### `.dto` 文件

13 个 `.dto` 中引用了被删字段（`metadata`、`profile`、`rawResponse`、`userMetadata`、`deletedAt`）或 `app_config` 三个 JSON 属性的，全部跟着改。

---

## DAO 层

### 租户隔离

```kotlin
@MappedSuperclass
interface AppScopedProps {
    @Column(sqlType = "binary")
    val appId: UUID
}

@Component
class AppScopedFilter : KFilter<AppScopedProps> {
    override fun filter(args: KFilterArgs<AppScopedProps>) {
        if (SystemScope.isActive()) return
        val appId = RequestContextHolder.currentOrNull()?.appId
            ?: throw IllegalStateException("AppScopedFilter: 当前线程没有 RequestContext")
        args.where(args.table.appId eq appId)
    }
}
```

拿不到 `RequestContext` 时**抛异常而不是放过查询**。静默跳过意味着一次上下文丢失就变成全租户扫描 + 跨租户数据返回，且没有任何信号。

跨租户操作（`agnes_key` 全局轮换、后台任务、运维脚本）走 `SystemScope.run { }`——一个 `ThreadLocal<Boolean>` 开关，所有使用点可 grep。

各 repo 里手写的 `where(table.appId eq appId)` 全部删除。

### 读模式

在 `ConnectionManager` 里落地：取到连接后按当前 `ReadMode` 设置 `tidb_read_staleness = -N`，`finally` 里无条件重置为 `0` 再归还连接。

**约束**：`tidb_read_staleness` 只作用于 autocommit 读。查询包在显式事务里时，正确写法是 `START TRANSACTION READ ONLY AS OF TIMESTAMP TIDB_BOUNDED_STALENESS(...)`，会话变量不生效。因此规定 **`BoundedStale` 只允许在无事务上下文中使用**，检测到当前有活跃事务时直接抛异常，不静默降级成强一致读——静默降级会让"我以为在用陈旧读所以延迟应该很低"变成一个查不出原因的性能问题。

Follower Read 通过连接串的 `sessionVariables=tidb_replica_read=closest-adaptive` 全局默认开启，业务侧无感知。

### 写冲突重试

```kotlin
@Component
class TxRunner(private val tx: TransactionTemplate) {
    fun <T> retryOnConflict(maxAttempts: Int = 3, block: () -> T): T
    fun <T> retryOnDuplicateKey(maxAttempts: Int = 3, block: () -> T): T
}
```

`retryOnConflict` 重试的错误码：`9007`（写冲突）、`8022`（事务提交失败）、`8028`（schema 变更导致事务失效）。指数退避 + 抖动。

`1062`（唯一键冲突）**不在默认重试范围**——它通常意味着业务语义上的重复。只有 `app_config` 那个"算 `revision + 1` 撞车"的场景需要，用单独的 `retryOnDuplicateKey` 在那一处显式调用。

调用方显式包裹，不做 AOP：`block` 会被执行多次，写它的人必须知道，否则迟早出现"重试了一个已经发过 MQ 消息的操作"。

### 游标分页

```kotlin
data class Cursor(val createdAt: Instant, val id: UUID) {
    fun encode(): String
    companion object { fun decode(s: String): Cursor }
}
```

在 `BaseCrudRepository` 里统一实现，生成的 SQL 形如（DESC 方向）：

```sql
WHERE app_id = ?                      -- 过滤器注入
  AND (created_at, id) < (?, ?)       -- 游标
ORDER BY created_at DESC, id DESC
LIMIT ?                                -- n+1 判断 hasMore
```

正好命中 `(app_id, created_at, id)` 索引。`BaseCrudService.findByCursor` 的假实现替换掉，`CursorQueryInput.sortBy` 的 `"_id"` 默认值删除。

### 幂等插入改 upsert

`CollectionItemRepository.insertIfAbsent` 的"先查再插"换成：

```kotlin
sql.entities.save(item) { setMode(SaveMode.UPSERT) }
```

`CollectionItem` 的 `collectionId` + `scanRecordId` 标 `@Key`，由 `TiDbDialect`（继承 `MySqlDialect`）生成 `INSERT ... ON DUPLICATE KEY UPDATE`，一条语句原子完成。

---

## 配置

```yaml
app:
  tidb:
    jdbc-url: ${TIDB_URL:jdbc:mysql://127.0.0.1:4000/ifmix_core_local}
    username: ${TIDB_USERNAME:root}
    password: ${TIDB_PASSWORD:}
    maximum-pool-size: ${TIDB_POOL_SIZE:20}
```

JDBC URL 上必须带的参数：

| 参数 | 理由 |
|---|---|
| `connectionTimeZone=UTC` | 时区双保险。`ScalarProvider` 已显式转换，裸 SQL 和 `NOW()` 走这条兜底 |
| `rewriteBatchedStatements=true` | 批量 insert 合并成单条多值语句。TiDB 每次往返都跨网络，对批量写吞吐影响很大 |
| `useServerPrepStmts=true` + `cachePrepStmts=true` | 配合 TiDB 默认开启的 prepared plan cache，省掉重复的 SQL 解析和计划生成 |
| `sessionVariables=tidb_replica_read=closest-adaptive` | Follower Read 全局默认开 |

Hikari 的 `maxLifetime` 必须小于 TiDB 和中间负载均衡器的空闲连接超时，否则会取到已被对端关闭的连接。

`app.datasource.writer` / `app.datasource.reader` 两段配置删除。

---

## 迁移文件与执行

现有 `V1__baseline.sql` ~ `V7__agnes_key.sql` 全部删除，从 V1 重新开始：

```
V1__auth.sql
V2__app_config.sql
V3__antique.sql
V4__collection.sql
V5__iap.sql
V6__agnes_key.sql
V7__todo_feedback.sql
```

baseline 是特例——全新建表、失败就 drop database 重来，所以允许一个文件多张表。**baseline 之后严格一个文件一个逻辑变更**，因为 TiDB DDL 隐式提交、不可回滚，失败时要能精确定位到哪一条。

三种执行入口，共用同一个镜像：

| 场景 | 方式 |
|---|---|
| 本地 | `./gradlew flywayMigrate`（挂成 `test` 任务的前置依赖） |
| CI | 同一个 Gradle task 作为独立步骤 |
| 生产 | K8s Job 跑应用镜像，带 `--app.db.migrate-and-exit=true`，migrate 完成即退出 |

应用正常启动时 `FlywayValidateRunner` 只调 `validate()`，版本不一致以非零码退出。

---

## 测试

`AbstractJimmerTest` 换成 `AbstractTiDbTest`：静态单例 `GenericContainer("pingcap/tidb:v8.5.3")`，暴露 4000 端口，`withReuse(true)`。整个测试套件共用一个容器和一个 database，Flyway 只跑一次。

镜像版本对齐 TiDB Cloud Starter 当前版本（8.5.3）——本地能力不能超过生产，否则会写出生产跑不了的代码。

测试间隔离用 `@BeforeEach` 里 `TRUNCATE` 相关表，不用 `@Transactional` 回滚。两个理由：一是要测真实的 upsert 冲突和重试，事务回滚会掩盖；二是 TiDB 的 `TRUNCATE` 是 DDL 实现（直接换 table id），比 MySQL 的删行快得多。

Java 侧没有官方 TiDB Testcontainers 模块（只有 Go 有），所以用 `GenericContainer` 自己包一层。

---

## 待验证清单

以下三项按文档判断可行，但必须在真 TiDB 上实测确认，结果会影响实现：

1. **`collection` 的 `IF()` STORED 生成列 + 其上的唯一索引能否创建。** TiDB 对生成列表达式有限制清单。若不支持，退路是在应用层维护一个普通的 `default_key` 列（写入时算好），代价是失去数据库层的强制保证
2. **行值比较 `(created_at, id) < (?, ?)` 是否走 `IndexRangeScan`。** 用 `EXPLAIN` 确认。若不走索引，退化写法是 `created_at < ? OR (created_at = ? AND id < ?)`
3. **`TTL = ... INTERVAL` 建表语句在 unistore 单节点模式下能否被接受。** 后台清理任务不执行没关系，但 DDL 必须能过；若过不了，TTL 要拆到独立迁移文件并在测试环境跳过

---

## 影响面清单

**删除的文件**

```
core-api/src/main/kotlin/.../infra/jimmer/ClusterProperties.kt
core-api/src/main/kotlin/.../infra/jimmer/ClusterRegistry.kt
core-api/src/main/kotlin/.../infra/jimmer/ReadWriteRoutingDataSource.kt
core-api/src/main/kotlin/.../infra/jimmer/ClusterInitializer.kt
core-api/src/main/kotlin/.../infra/jimmer/JimmerConfig.kt        （重写为 SqlClientConfig）
core-api/src/main/resources/db/migration/V1..V7__*.sql            （重写）
core-api/src/test/kotlin/.../common/jimmer/cluster/ClusterRegistryTest.kt
core-api/src/test/kotlin/.../common/jimmer/cluster/ReadWriteRoutingDataSourceTest.kt
core-api/src/test/kotlin/.../common/db/MongoClusterResolverTest.kt
core-api/src/test/kotlin/.../common/db/MongoSerializationTest.kt
core-api/src/test/kotlin/.../common/db/TestDocument.kt
core-api/src/test/kotlin/.../support/AbstractMongoTest.kt
```

**依赖变更**（`core-api/build.gradle.kts`）

移除 `org.postgresql:postgresql`、`org.flywaydb:flyway-database-postgresql`、`org.testcontainers:postgresql`、`com.h2database:h2`；加入 `com.mysql:mysql-connector-j`、`org.flywaydb:flyway-database-tidb`、`org.testcontainers:testcontainers`。

**需要改动的业务代码**

| 文件 | 原因 |
|---|---|
| `AuthService.kt` | `RequestContext` 类型变化；删除 `profile`/`metadata`/`providerMetadata` 赋值；`userMetadata["picture"]` 改写 `avatarUrl` |
| `AuthProviderIdentityRepository.kt` | 删除 `userMetadata`/`providerMetadata` 参数 |
| `AppUserRepository.kt` | 删除 `metadata` 赋值 |
| `AppConfigRepo.kt` | 删除 `toFlat()`；`getByAppId` 去掉 try/catch；反查改两步 + 校验 |
| `AppConfigRepository.kt` | `findCurrentByAppId` 改 `ORDER BY revision DESC LIMIT 1`；`findByBundleId`/`findByAndroidPackage` 重写 |
| `IapService.kt` | 删除 `rawResponse` 赋值 |
| `CollectionItemRepository.kt` | `insertIfAbsent` 改 UPSERT |
| `CollectionService.kt` | 删除 `CollectionItem` 时不再依赖级联 |
| `TodoService.kt` | 删除 `Todo` 时显式删 `TodoItem` |
| `BaseCrudRepository.kt` / `BaseCrudService.kt` | 加 `ReadMode` 参数；实现真游标分页 |
| 所有 repo | 删除手写的 `where(table.appId eq appId)` |
| `HeaderValidationInterceptor.kt` | 增加 `x-install-id` 的 UUID 格式校验 |
| `RequestContextArgumentResolver.kt` | 解析成 `UUID` |
| `TransactionConfig.kt` | 从空占位改成 `TxRunner` 装配 |

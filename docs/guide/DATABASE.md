# 数据库约定

## 表名规则

- 统一前缀: `core_`（区分本服务与未来其它服务/schema）
- 模块前缀: `core_{module}_`（如 `core_auth_`, `core_demo_`, `core_pay_`）
- 实体名小写下划线
- 关系表: `core_{module}_{from}_to_{to}_relation`

## 全部表

| 模块 | 表名 | 级别 | Entity |
|------|------|------|--------|
| auth | `core_auth_idp` | 全局 | Idp |
| auth | `core_auth_idpidentity` | 全局 | IdpIdentity |
| auth | `core_auth_identity` | project | AuthIdentity |
| auth | `core_auth_identity_to_idpidentity_relation` | project | AuthIdentityIdpRelation |
| auth | `core_auth_project_to_idp_relation` | project | ProjectToIdpRelation |
| auth | `core_auth_refreshtoken` | project | RefreshToken |
| customer | `core_customer` | project | Customer |
| demo | `core_demo_todo` | project | Todo |
| demo | `core_demo_todo_item` | project | TodoItem |
| project | `core_project_config_revision` | project | ProjectConfigRevision |
| project | `core_project_info` | 全局 | ProjectInfo |
| ai | `core_ai_scan_record` | project | ScanRecord |
| ai | `core_ai_scan_deep_research` | project | ScanDeepResearch |
| ai | `core_ai_scan_collection` | project | ScanCollection |
| ai | `core_ai_scan_collection_item` | project | ScanCollectionItem |
| ai | `core_ai_customer_scan_metrics` | project | CustomerScanMetrics |
| ai | `core_ai_api_key` | 全局 | AiApiKey |

> 2026-10-01 由 `core_ai_agnes_key` 通用化改名（V8），新增 `provider` 列（10=AGNES，码表 `ApiProviders`）。
> 设计与触点见 [design/ai/api-key-pool](../design/ai/api-key-pool.md)「表结构」。
| pay | `core_pay_subscription` | project | Subscription |
| pay | `core_pay_store_notification` | project | StoreNotification |
| media | `core_media_file_record`（V16 由 `core_media_upload_record` 改名） | project | UploadRecord |
| cs | `core_cs_feedback` | project | Feedback |
| cs | `core_cs_support_request` | project | SupportRequest |
| install | `core_install` | project | Install |
| install | `core_install_customer_relation` | project | InstallCustomerRelation |
| install | `core_install_attestation` | project | InstallAttestation |

> `install_id`（`InstallIdProps`，entity/common）：客户端安装标识，由 `x-install-id` header 上报，服务端仅记录（可伪造，不用于鉴权），用于行为分析。已铺到 `core_ai_scan_record` / `core_ai_scan_collection` / `core_cs_feedback` / `core_cs_support_request`（均可空）。

> `app_version` / `ota_version`（`ClientVersionProps`，entity/common）：客户端版本快照，由 `x-app-version` / `x-ota-version` header 上报，原样透传、可空，仅用于按版本聚合分析/回归定位。`ota_version` 形如 `runtimeVersion-buildNumber-otaSeq`（如 `1-23-3`）。已铺到 `core_cs_feedback` / `core_cs_support_request`。

## Auth 身份模型映射

跨模块均为逻辑外键 UUID（不用 `@ManyToOne`）：

| 关系 | 方向 / 列 | 基数 | 说明 |
|------|-----------|------|------|
| Customer → AuthIdentity | `core_customer.auth_identity_id` | N:1 | 匿名未登录为 null；customer 注销后可新建、复用同一账号 |
| AuthIdentity ↔ IdpIdentity | 关系表 `core_auth_identity_to_idpidentity_relation`（`auth_identity_id` / `idp_identity_id`） | M:N | 见下 |
| RefreshToken → 主体 | `actor_type`(10=customer/20=manager) + `actor_id` | — | 主体无关，不直接绑 customer |
| IdpIdentity → Idp | `core_auth_idpidentity.idp_id`（可选） | N:1 | email/phone 等内建身份可无 idp |
| ProjectToIdpRelation | `project_id` + `idp_id` | M:N | project 启用了哪些 IDP |

**M:N 双向（关系表按 `project_id` 隔离）**：
- 一个 `IdpIdentity`（全局，跨 project）→ 多个 `AuthIdentity`（每 project 一个）：反查唯一索引 `(project_id, idp_identity_id) WHERE deleted_at IS NULL`。
- 一个 `AuthIdentity`（project 级）→ 多个 `IdpIdentity`（同 app 多 provider：Google+Apple…）：正查索引 `(project_id, auth_identity_id)`。

> `IdpIdentity`/`Idp` 全局跨 project，其余身份表为 project 级。账号权威资料（姓名/邮箱/手机/metadata）在 `AuthIdentity`；`Customer` 只保留匿名/合并语义 + `authIdentityId`。详见 [AUTH_DESIGN.md](AUTH_DESIGN.md)。

## 主键

- UUIDv7 (时间有序，支持游标分页)
- 生成: `UuidV7.generate()`

## UUID 表示

- **PG/Jimmer**: 原生 UUID (16 bytes)
- **API 输出**: 原始 36 字符格式
- **objectKey（URL 场景）**: 22 位 Base58（短、URL-safe）

## 游标分页

- 简单: `WHERE id < cursor ORDER BY id DESC LIMIT n+1`
- 复合 cursor（sortBy 非 id 时）: `{sortValue},{id}` 编码
- 条件: `(col < val) OR (col = val AND id < uuid)`

## 读写分离

- ClusterRegistry + ClusterRouter + ReadWriteRoutingDataSource
- Query → reader, Mutation → writer

## 软删除

- `deletedAt` 列（继承 SoftDeletableProps）

## 枚举

- **GraphQL**: input/output 全部 `Int`，schema 注释写含义
- **PG**: SMALLINT
- **Kotlin**: `val status: Int`
- **常量**: 放 model class 嵌套 object，跨模块的放 `entity/common/`
- **编码规则**: 0 保留不用，从 10 开始步长 10
- **typealias 提可读性**: 语义编码字段用 `typealias Xxx = Int` + 常量 `object Xxxs`（如 `ActorType`/`FeedbackReason`）。
  编译后即 `Int`，对 Jimmer(KSP)/GraphQL(DGS) 完全透明，DB 列/wire 不变；蓝绿发布老节点读到
  未知 code 走 `when else` 降级而非崩溃（不同于 enum）。别名只提可读性，不带来类型安全。

### 枚举码表登记

#### `ImageRef.category`（图片分类，存于 `core_ai_scan_record.image_keys` JSONB）

| Code | 名称 | 说明 |
|------|------|------|
| 0 | MAIN | 主图 / 默认（初次扫描、未指定分类时的默认值） |
| 10 | FRONT | 正面 |
| 20 | BACK | 背面 |
| 30 | BOTTOM | 底部 / 底面 |
| 40 | MAKER_MARK | 款识 / 签名 |
| 50 | DAMAGE | 损伤 / 磨损 |
| 60 | DIMENSIONS | 尺寸 / 比例（带参照物） |
| 70 | PRICE_TAG | 价签 |
| 80 | DOCUMENTS | 文件 / 来源证明 |
| 90 | DETAIL | 局部细节（通用，可选） |
| 1000 | OTHER | 其它 |

#### 主体 / 身份类

| typealias | 常量 object | 码表 |
|-----------|------------|------|
| `ActorType` (entity/common) | `ActorTypes` | 10=CUSTOMER, 20=MANAGER |
| `IdpType` (entity/common) | `IdpTypes` | 10=APPLE, 20=GOOGLE |
| `Platform` (entity/common) | `Platforms` | 10=APPLE, 20=GOOGLE |
| `LoginMethod` (entity/auth) | `LoginMethods` | 10=EMAIL, 20=PHONE, 30=IDP |

#### 业务类

| typealias | 常量 object | 码表 |
|-----------|------------|------|
| `ContentType` (dto/common) | `ContentTypes` | 10=IMAGE_JPEG, 20=IMAGE_PNG, 30=IMAGE_WEBP |
| `ScanStatus` (entity/ai) | `ScanStatuses` | 10=CREATED（DB 列默认）, 20=READY |
| `FeedbackTopic` (entity/cs) | `FeedbackTopics` | 0=UNKNOWN, 10=SCAN, 20=DEEP_RESEARCH, 30=APP |
| `MediaType`（媒体大类，`MediaRef.type`）(entity/common) | `MediaTypes` | 0=UNKNOWN, 10=IMAGE, 20=VIDEO, 30=AUDIO, 40=DOCUMENT |
| `SupportRequestStatus` (entity/cs) | `SupportRequestStatuses` | 10=OPEN, 20=IN_PROGRESS, 30=PENDING_CUSTOMER, 40=RESOLVED, 50=CLOSED |
| `SupportRequestCategory` (entity/cs) | `SupportRequestCategories` | 0=UNSPECIFIED, 10=BUG, 20=FEATURE_REQUEST, 30=ACCOUNT, 40=PAYMENT, 50=CONTENT_ERROR, 1000=OTHER（允许客户端传未登记值） |
| `AiApiKeyType` (entity/ai) | `AiApiKeyTypes` | 10=PERSONAL（`sk-` 前缀）, 20=ENTERPRISE（`wk-` 前缀） |
| `AiApiKeyProvider` (entity/ai) | `ApiProviders` | 10=AGNES |

#### `Feedback.reasons`（反馈原因，多选，存于 `core_cs_feedback.reasons` PG `smallint[]`）

typealias `FeedbackReason` / 常量 `FeedbackReasons`。多选数组，Jimmer 原生数组映射
（`Array<Int>` + `@Column(sqlElementType = "smallint")`）。

| Code | 名称 |
|------|------|
| 0 | UNKNOWN |
| 10 | LIKED |
| 20 | PRICE_TOO_HIGH |
| 21 | PRICE_TOO_LOW |
| 22 | PRICE_MISSING |
| 23 | PRICE_UNREASONABLE |
| 30 | WRONG_IDENTIFICATION |
| 40 | FEATURE_REQUEST |
| 41 | MORE_RECOMMENDATIONS |

## FilterGroup 动态查询

```graphql
input FilterGroup {
  and: [FilterExpr!]
  or: [FilterExpr!]
}
input FilterExpr {
  field: FieldFilter
  group: FilterGroup
}
input FieldFilter {
  field: String!
  op: FilterOp!    # EQ/NE/GT/GTE/LT/LTE/IN/NIN/LIKE/IS_NULL/IS_NOT_NULL
  value: JSON
  values: [JSON!]
}
```

`FilterGroupResolver` 转为 Jimmer 谓词，通过 `TypedProp.Scalar` 白名单校验 + 类型自动转换。

## CommonFindOptions

```graphql
input CommonFindOptions {
    filter: FilterGroup
    cursor: String
    sortBy: String
    sortDirection: SortDirection  # ASC / DESC
    limit: Int
}
```

后端 `ProjectCrudRepoTemplate.findByOptions` 统一处理，需声明 `filterable` 和 `sortable` 白名单。

## Flyway

- 当前 migration 目录为 V1–V5（历史迁移已压缩进 baseline），不可回退
- 迁移文件: `core-api/src/main/resources/db/migration/`
- **手动执行**（不再随应用启动自动 migrate）：`./gradlew :core-api:flywayMigrate`
  - 连接由 `DB_URL`/`DB_USER`/`DB_PASSWORD` 决定（默认本地 `core_api_local`）
- **修 checksum**：`./gradlew :core-api:flywayRepair`（重算历史表 checksum 使之与脚本一致，不改表结构）
- 版本一览:
  - V1 baseline（压缩后的基线，含全部建表 + 种子数据）
  - V2 所有业务表加 `core_` 前缀（`ALTER TABLE ... RENAME TO core_*`，21 张；不改列/约束/索引名，不动 `flyway_schema_history`）
    - 本机 `core_api_local` 与线上 `app_us1/core_api` **均已应用**（21 张表全部改名、数据随 RENAME 保留、物理 FK 自动跟随）。
    - 线上是直接执行 V2 SQL + 手插 V2 历史记录（未走 `flywayMigrate`，避免触发早期 V1 checksum 差异校验）。
    - 两库 flyway 历史现已完全一致：V1=`-1432007747`、V2=`-1291542121`（本地 V1 原 checksum 为空，已用 `flywayRepair` 对齐；无需改线上）。
  - V3 `core_cs_feedback` / `core_cs_support_request` 各加 `app_version` / `ota_version`（varchar(64)，可空；`ClientVersionProps`）。尚未在任何库执行，需 `./gradlew :core-api:flywayMigrate`。
  - V4 `core_customer` 加 `scan_count` / `deep_research_count`（integer 默认 0，业务侧原子自增）。
  - V9 新建 `core_ai_customer_scan_metrics`（每 customer 一行，`customer_id` 唯一，行不存在 = 计数 0，首次写入懒建），回填 V4 两列中非零的计数；`core_customer` 旧列暂留（实体不再映射），发布完成后另起迁移删除。
  - V5 install 追踪：新建 `core_install` + `core_install_customer_relation`。
  - V6 Install ID 收敛：`core_install.id = API installId = JWT iid`，删除冗余 `core_install.install_id`；四张 Customer 业务表 `install_id` 安全转为 nullable UUID，新写入由应用层强制 token iid。已在本地 PG 18.1 从 V5→V6 验证，未删除任何 resource。
  - V14 `core_project_server_config`（project 级服务端专属配置 JSONB，不下发前端；含 `fcm_config` / `app_attest_config`）。
  - V15 install attestation（一期 1a）：`core_project_server_config` + `app_attest_config` JSONB；`core_install` + `store_type` INT NULL；新建 `core_install_attestation`（只存 VALID 长期凭证/绑定，含 `attestation_object` 回填列 + 4 个部分/唯一索引）。设计见 `docs/design/attest/install-attestation.md` §5.4。

## Install 平台证明（V15，install attestation 一期 1a）

详见 `docs/design/attest/install-attestation.md`（§0 v5 修订表 + §5.4/§5.8/§5.9）。

- **`core_install_attestation`**：只存 VALID 的长期凭证与绑定（失败尝试不入表，只进日志）。`(project_id, provider, subject)` 部分唯一索引（subject 非空时）；`provider` 110=APP_ATTEST / 120=PLAY_INTEGRITY；`status` 10=ACTIVE / 20=BLOCKED / 30=RETIRED；`sign_count BIGINT NOT NULL DEFAULT 0`（recover 条件更新依赖非 NULL）。
  - **`attestation_object`**：原始 attestation（≤16KB），core-job 回填任务向 Apple `POST /v1/attestations` 换 receipt 成功后清空（§5.8 / §2 决策 1）。
  - **`receipt` / `fraud_metric` / `next_refresh_at` / `refresh_failure_count`**：core-job 回填 + fraud metric 刷新写入；`receipt_expires_at` 一期不写（保留列）。
  - **`signals` JSONB NOT NULL**：验证信号（固定键集合）；**`evidence` JSONB NULL**：佐证（不含原始 token），90 天后由 core-job 清空。
- **`core_install.store_type`**：安装来源商店（10=APP_STORE / 20=GOOGLE_PLAY），客户端上报、write-once、仅统计（§5.9）。
- **`core_project_server_config.app_attest_config`**：per-project 证明配置 JSONB（null=关），含 `mode`（OFF/OBSERVE/ENFORCE）与 `ios`/`android` 子对象；**服务端专属、绝不下发**（沿用该表约束）。

## Install 设备追踪（V5）

详见 `docs/design/install/install-tracking.md`。

- **`core_install`**：V6 起主键 `id` 即 API `installId` 和 JWT `iid`（服务端 UuidV7），不再有独立 `install_id` 列。`platform`(Int 10/20/30) / `device_info`(jsonb) / `app_version` / `ota_version` / `locale` / `country` / `currency` 来自请求 header（create 与 update 都写，仅覆盖非空）。`reg_ip` **write-once**：仅 createInstall 写入（`clientIp`），updateInstall 不改。`firebase_install_id` / `fcm_token` 客户端后补。
- **`core_install_customer_relation`**：`(install_id, customer_id)` **全局唯一**（一对关系永远一行），`deleted_at` 软删（`@LogicalDeleted`）：null=当前绑定 / not null=已解绑。bind/unbind 复用同一行翻转 `deleted_at`（re-bind 复活软删行，需 `filters { setBehavior(..., LogicalDeletedBehavior.IGNORED) }` 绕过默认过滤）。一个 install 同时只绑一个 customer（绑新的前软删该 install 其它有效关系）。
- **关系维护挂点**（`AuthAggHandler`）：createAnonymousCustomer 要求有效 iid（token 类型不限）并绑定；login 用 customer/install token 的 iid 绑定最终 owner；refresh 续期并对 customer refresh token 补绑 actor↔install（bind 幂等），install refresh token 不绑；logout 有 iid 时解绑，legacy token 缺 iid 时只撤销会话；requestAccountDeletion 软删该 customer 全部关系。core-job cleanup 本期完全不改，后续另案设计。

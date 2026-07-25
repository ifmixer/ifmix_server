# ifmix 后端重写：地基 + 首个纵向切片（core-api 服务）

- 日期：2026-07-26
- 状态：已批准，待编写实现计划
- 范围：建立 `ifmix_server` 多项目仓库的地基，实现 `core-api` 服务的项目骨架、HTTP 约定、通用 Repo/Service 抽象、MongoDB 接入、多租户机制、OpenAPI、虚拟线程，并用 `todo` 模块打通端到端。

## 背景与目标

用 Java + Spring + MongoDB 重写现有 Node 后端（`ifmix_apps/server/core`，栈为 Hono + Drizzle + PostgreSQL）。最终目标是覆盖除 auth 外的全部模块（antique、iap、todo），但分步实现：本规格先打通地基 + 一个纵向切片，后续模块各出独立规格。

- **重写而非增量迁移**：DB 从关系型（PostgreSQL）重新设计为文档型（MongoDB）。
- **auth 暂缓**：本轮不实现登录鉴权。
- **虚拟线程**：用阻塞式 Spring MVC + 虚拟线程，不引入响应式/协程。
- **契约风格调整（非照搬）**：现有移动端消费当前契约；新后端保留信封与整体风格，但调整 HTTP 方法语义（见下）。

## 技术栈

- **语言/运行时**：Java 25（最新 LTS）。
- **框架**：Spring Boot 4.1（基于 Spring Framework 7），Spring MVC（阻塞式）。
- **并发**：虚拟线程，`spring.threads.virtual.enabled=true`（每请求一个虚拟线程，阻塞式 Mongo 驱动与之兼容）。
- **数据库**：MongoDB（副本集）+ Spring Data MongoDB。副本集使得真正需要跨文档原子性时可用多文档事务。
- **构建**：Maven 多模块聚合。
- **API 文档**：springdoc-openapi（代码优先）。
- **校验**：Jakarta Bean Validation。
- **测试**：JUnit 5 + AssertJ + Testcontainers（MongoDB 副本集）。
- **基础包名**：`com.ifmix.api.core`。

## 仓库结构（Maven 多模块聚合）

根目录仅放聚合父 POM；`core-api` 是第一个服务模块，未来服务作为兄弟模块加入父 POM 的 `<modules>`。

```
ifmix_server/
  pom.xml                      # 聚合父 POM：dependencyManagement + Spring Boot 4.1 BOM + Java 25 + pluginManagement
  core-api/
    pom.xml                    # core-api 服务模块（打包成可运行 jar）
    src/main/java/com/ifmix/api/core/
      CoreApplication.java
      common/
        http/     Envelope, ApiError, ErrorCode, GlobalExceptionHandler,
                  RequestContext, RequestHeaders, RequestContextArgumentResolver,
                  HeaderValidationInterceptor, EnvelopeResponseAdvice
        db/       BaseRepository, BaseAppRepository, Page, CursorQuery, SoftDelete 支持
        service/  BaseAppService
        config/   MongoConfig(转换器), OpenApiConfig(分组), WebConfig(拦截器/参数解析器)
      modules/
        todo/     TodoDocument, todo DTO, TodoService
      bff/
        customer/       CustomerTodoController
        appadmin/       （骨架占位）
        platformadmin/  （骨架占位）
        webhooks/       （骨架占位）
    src/main/resources/application.yml
    src/test/java/com/ifmix/api/core/...
```

- **父 POM**：统一 Java 版本、Spring Boot BOM、依赖版本管理、插件配置。不含业务代码。
- **通用抽象暂留 `core-api` 内**：`BaseRepository`/`Envelope`/`RequestContext` 等先不抽独立库。等第二个服务真正需要复用时，再抽成 `common` 模块（YAGNI，避免提前抽象）。

## HTTP 对外契约

保留现有信封与 **URL 形状不变**，仅叠加用 HTTP 方法编码读写语义。

- **URL 形状（保持原样，含 `query`/`mutation` 段）**：`/{bff}/{service}/{query_type}/{module}/{action}`，`query_type ∈ query | mutation`。
- **方法编码读写语义（新增约定）**：`PUT` = query（读，可带查询请求体），`POST` = mutation（写）。方法与 URL 的 `query_type` 段一致、互相印证。
  - `PUT  /customer/core/query/todo/findMany`
  - `PUT  /customer/core/query/todo/getById`
  - `POST /customer/core/mutation/todo/createOne`
  - `POST /customer/core/mutation/todo/updateOne`
  - `POST /customer/core/mutation/todo/deleteById`
- **BFF**：`customer`、`app-admin`、`platform-admin`（跨租户）、`webhooks`。各 BFF 声明各自要暴露的路由子集。
- **请求体**：裸 DTO，不包信封。通用按 id 请求体用 `ByIdRequest { id }`。
- **响应信封**：`{ code, msg, data }`，`code` 为字符串（`"200000"`、`"400000"`、`"404000"` 等）。
  - 用 `EnvelopeResponseAdvice`（实现 `ResponseBodyAdvice`）自动把 controller 返回的 DTO 包成信封，controller 方法保持返回纯 DTO / `Page<T>`（对标现有 `jsonRoute` 自动 `ok()` 的工效）。
- **action 命名**：与 `BaseAppService` 一致的通用短名——`getById`、`findMany`、`createOne`、`updateOne`、`deleteById`。

### 错误处理

- `ApiError(ErrorCode, message?)`：把语义 `ErrorCode` 映射为外部字符串 code + HTTP 状态。
- `GlobalExceptionHandler`（`@RestControllerAdvice`）：
  - `ApiError` → 映射后的 code + 状态 + 信封。
  - Bean Validation 失败（`MethodArgumentNotValidException` / `ConstraintViolationException`）→ 400，带**字段级**消息（对标现有 `formatZodError`，如 `patch.title: 长度不足`）。
  - 请求头校验失败 → 400，带字段级消息（如 `x-app-id: 无效输入`）。
  - 其它未捕获异常 → 500；非生产环境返回 `err.message`，生产环境返回 `"internal error"`。

## 请求头与请求上下文（显式参数传递）

- `RequestHeaders`：
  - `x-app-id`（必填）
  - `x-install-id`（可选）
  - `x-lang` / `x-currency` / `x-country` / `x-native-version` / `x-js-version`（可选字符串）
  - `x-client-platform`（枚举 `android|ios|web`，可选）
- `HeaderValidationInterceptor`（`HandlerInterceptor`）：在业务处理前统一校验请求头，非法值 400。挂到 `customer` + `app-admin`；`platform-admin` 不挂。文档/swagger 路由不校验。
- `RequestContextArgumentResolver`（`HandlerMethodArgumentResolver`）：把校验后的请求头组装成 `RequestContext`，作为 controller 方法参数注入。
- `RequestContext`：不可变值对象，携带 `appId`、`installId`、`lang`、`currency`、`country`、`clientPlatform`、（未来）`userId` 等。
- **显式传递**：controller 拿到 `RequestContext` 后显式传给 service（`service.findMany(ctx, input)`），service 再传给 repo。租户归属永远显式可查、可测；不使用 ThreadLocal 隐式上下文。

## 通用 Repo / Service 抽象（继承复用，避免重复代码）

可复用积木通过**继承**提供：模块 repo/service 直接 `extends` 基类，白拿全套通用 CRUD，避免每个模块重复委托/转发代码。基于 Spring Data MongoDB（`MongoTemplate` 的动态 `Criteria` 便于统一注入租户过滤）。

### BaseRepository&lt;T&gt;

基于 `MongoTemplate` 的通用 CRUD 基类，**不关注租户**。构造时传入文档类型 `T` 与集合名，以及可选 `softDelete` 开关。

- `insertOne(ctx, entity)` / `insertMany(ctx, entities)` → `void`
- `updateById(ctx, id, patch)` → `boolean`（是否命中）
- `deleteById(ctx, id)` → `boolean`（软删开启时改为置 `deletedAt`）
- `getById(ctx, id, readOptions)` → `T`（默认未命中抛 `NOT_FOUND`）
- `findMany(ctx, cursorQuery)` → `Page<T>`
- 提供受保护钩子 `extraCriteria(ctx)`：供子类注入额外过滤条件（如租户）。

### BaseAppRepository&lt;T&gt; extends BaseRepository&lt;T&gt;

**自动注入 `appId = ctx.appId` 租户过滤**（覆盖 `extraCriteria`）：所有按 id 操作与 `findMany` 都强制追加租户条件，默认安全、模块无法绕过。文档必须带 `appId` 字段。租户集合一律用它；不带租户或需跨租户的才用裸 `BaseRepository`。

### BaseAppService&lt;T&gt;

持有一个 `BaseAppRepository<T>`，用 `newEntityForCreate` 构造完整实体（`appId` + `createdAt/updatedAt` 时间戳）后委托 repo，暴露通用写/读方法：

- `createOne(ctx, input)` → 新 id
- `updateById(ctx, id, patch)` → `boolean`
- `deleteById(ctx, id)` → `boolean`
- `getById(ctx, id, readOptions)` → `T`
- `findMany(ctx, cursorQuery)` → `Page<T>`

### 模块 service 用继承复用

**模块 service 直接 `extends BaseAppService<T>`**，从而无需重复编写通用 CRUD 的转发代码；只在需要定制的地方（子实体、事务、多集合、关系读）新增或 `@Override` 对应方法。例如 `TodoService extends BaseAppService<TodoDocument>`，仅覆写 `createOne`（内嵌 items）等定制点，其余 CRUD 直接继承。

> 取舍：这里选择继承而非组合，目的是消除每个模块 service 对通用方法的样板转发代码。基类的公开面即模块 service 的通用面，定制通过 `@Override` 精确覆盖。若某模块的定制导致基类方法签名严重不契合，再针对该模块改用组合。

### 软删除

- 业务集合逻辑删除：`deletedAt` 字段（`Instant`，未删为 `null`）。
- `softDelete=true` 时：`deleteById` 改为 `UPDATE deletedAt=now`；`getById`/`updateById`/`deleteById` 的 id 过滤追加 `deletedAt == null`；`findMany` 的条件追加 `deletedAt == null`（与租户条件合并）。
- 未开启的 repo 行为完全不变。

### 游标分页

- 按 `_id`（`ObjectId`）keyset 分页：`ObjectId` 时间有序。
  - 降序（默认，最新在前）：`_id < cursor`
  - 升序：`_id > cursor`
- 取 `limit + 1` 条判断 `hasMore`；`limit` 默认 20，上限 100。
- 返回 `Page<T> { items, nextCursor, hasMore }`；`nextCursor` 为最后一条的 `_id`（hex 字符串），无更多则 `null`。
- 地基阶段 `CursorQuery` 支持：`cursor`、`order`（asc|desc）、`limit`、可选简单等值过滤。更丰富的 JSON 灵活查询（字段投影/关系读）作为后续增强，需要时加列白名单。

## MongoDB 与序列化

- **`_id`**：Mongo 原生 `ObjectId`。对外 JSON 序列化为 24 位 hex 字符串（DTO 用 `String id`）。`ObjectId` 自带时间有序 → 游标分页按 `_id` 直接可用。
- **时间**：文档内用 `Instant`（存为 BSON `Date`）。DTO 对外用 **epoch 毫秒 `long`**（DTO 组装时在文档 `Instant` 与 DTO `long` 间转换，保持与现有契约一致）。
- **建模按场景判断**：
  - 聚合边界内、随父一起读写、有界的子实体 → **内嵌**（如 `todo.items`），一次读写原子完成，规避多文档事务。
  - 需独立查询、可无限增长、跨聚合的 → **独立集合 + 引用**（如未来的 `scan_record` 扫描历史）。
- **多租户**：所有租户集合含 `appId` 字段 + `(appId, _id)` 复合索引；`BaseAppRepository` 强制注入。
- **读一致性**：写后回读走主库（`primary`/`primaryPreferred` 读偏好），避免副本延迟读不到刚写数据。
- `MongoConfig`：注册 `ObjectId ↔ String`、`Instant ↔ epoch ms` 的转换器与 Jackson 序列化器。

## 首个纵向切片：`todo` 模块

- `TodoDocument`：`_id`(ObjectId)、`appId`、`title`、`done`、内嵌 `items: [{ id, content, done }]`、`createdAt`、`updatedAt`、`deletedAt`。
- `TodoService extends BaseAppService<TodoDocument>`（继承复用通用 CRUD，仅覆写定制点）：
  - `createOne`：内嵌 items 一次写入（单文档原子，无需事务），返回新 id。
  - `getById`：租户 + 软删过滤，未命中抛 `NOT_FOUND`。
  - `findMany`：游标分页。
  - `updateOne`：id 与 patch 分离，返回是否命中。
  - `deleteById`：软删，返回是否命中。
- `CustomerTodoController`（customer BFF）：暴露上述五个 action，方法语义按 `PUT`=query / `POST`=mutation。
- **验证目标**：租户强制注入、信封自动包装、方法路由、OpenAPI 文档生成、Bean Validation 字段级错误、虚拟线程、Testcontainers 集成测试全链路跑通。

## OpenAPI

- 每个 BFF 一个 `GroupedOpenApi`（`customer` / `app` / `platform`），各自独立文档与 Swagger UI。
- 请求头注册为统一 apiKey security scheme + 全局 `security`，形成一个统一的 "Authorize" 弹窗（填一次，全接口生效），并开启 `persistAuthorization`。
- 前端通过生成的 `openapi.json` → openapi-typescript 生成类型化 client。不直接共享后端 DTO。

## 测试

- **单元测试**：纯函数 / helper（如信封构造、游标编解码、实体构造）与被测类同包，JUnit 5 + AssertJ。
- **集成测试**：Testcontainers 启动 MongoDB（副本集），验证租户隔离、软删、游标分页、事务（后续模块）等真实行为。测试基类提供懒初始化的 `MongoTemplate`、清库、构造最小 `RequestContext` 的助手。
- 每次改动后跑 `mvn -q verify`（编译 + 测试）。

## 后续规格路线图（分步实现 → 最终覆盖除 auth 外全部）

1. **本规格**：地基 + `todo` 纵向切片。
2. **app config / app info**：共享配置（antique、iap 依赖），含配置版本化。
3. **antique**：Redis 限流 + S3/R2 预签名 + Spring AI 2.0 扫描编排（限流 → 取图 → AI → 落库 → 失败退还）+ 扫描历史（独立集合，游标分页）。
4. **iap**：Apple/Google 内购校验 + `webhooks` BFF + 权益（entitlement）+ 订阅。
5. **auth**（暂缓）：EdDSA JWT + JWKS + Google/Apple SSO + 跨 app 设备密钥交换 + refresh token 轮换（用副本集事务保证原子撤销）。

## 关键决策与取舍

- **重写而非迁移**：借机把关系模型重构为文档模型（内嵌规避多数事务），而非机械照搬表结构。
- **继承复用通用 CRUD（`Base*` 基类）**：模块 repo/service 直接 `extends`，消除样板转发代码；定制通过 `@Override`。命名统一为 `Base*` 前缀以表达"为继承/扩展而设计"。
- **URL 形状不变 + 方法叠加语义**：保留原有 `/{bff}/{service}/{query_type}/{module}/{action}`（含 `query`/`mutation` 段）不删，仅新增"`PUT`=query / `POST`=mutation"约定，方法与 URL 段互相印证，移动端仅需改方法。
- **虚拟线程 + 阻塞 MVC**：避开响应式/协程的心智负担；阻塞式 Mongo 驱动在虚拟线程下天然高并发。
- **显式 `RequestContext` 参数**：牺牲一点签名简洁，换取租户归属的可查性、可测性，符合安全底线"默认安全、显式可审"。
- **自研泛型 `MongoTemplate` 基类**而非 `MongoRepository` 接口式仓储：租户自动注入必须无法绕过，动态 `Criteria` 最能保证每个查询都强制追加 `appId`。
- **父 POM 但暂不抽共享库**：统一版本/插件是低成本高收益；共享代码延迟到有第二个真实消费者时再抽（YAGNI）。
- **`ObjectId` 主键**：原生、省空间、时间有序、游标分页零成本；对外暴露 24 位 hex 字符串（契约风格允许调整，移动端配合小改）。

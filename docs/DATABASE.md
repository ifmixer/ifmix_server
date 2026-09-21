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
| ai | `core_ai_agnes_key` | 全局 | AgnesKey |
| pay | `core_pay_subscription` | project | Subscription |
| pay | `core_pay_store_notification` | project | StoreNotification |
| media | `core_media_upload_record` | project | UploadRecord |
| cs | `core_cs_feedback` | project | Feedback |
| cs | `core_cs_support_request` | project | SupportRequest |

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
| `AgnesKeyType` (entity/ai) | `AgnesKeyTypes` | 10=PERSONAL（`sk-` 前缀）, 20=ENTERPRISE（`wk-` 前缀） |

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

- 当前 migration 目录为 V1–V3（历史迁移已压缩进 baseline），不可回退
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

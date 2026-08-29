# 数据库约定

## 表名规则

- 模块前缀: `{module}_`（如 `auth_`, `demo_`, `pay_`）
- 实体名小写下划线
- 关系表: `{module}_{from}_to_{to}_relation`

## 全部表

| 模块 | 表名 | 级别 | Entity |
|------|------|------|--------|
| auth | `auth_idp` | 全局 | Idp |
| auth | `auth_idpidentity` | 全局 | IdpIdentity |
| auth | `auth_app_to_idp_relation` | app | AppToIdpRelation |
| auth | `auth_appuser_to_idpidentity_relation` | app | AppUserToIdpIdentityRelation |
| auth | `auth_appuser_refreshtoken` | app | AppUserRefreshToken |
| auth | `auth_appuser_to_install_relation` | app | AppUserToInstallRelation |
| user | `user_appuser` | app | AppUser |
| demo | `demo_todo` | app | Todo |
| demo | `demo_todo_item` | app | TodoItem |
| app | `app_config_revision` | app | AppConfigRevision |
| app | `app_info` | 全局 | AppInfo |
| ai | `ai_scan_record` | app | ScanRecord |
| ai | `ai_scan_deep_research` | app | ScanDeepResearch |
| ai | `ai_scan_collection` | app | ScanCollection |
| ai | `ai_scan_collection_item` | app | ScanCollectionItem |
| ai | `ai_agnes_key` | app | AgnesKey |
| pay | `pay_subscription` | app | Subscription |
| pay | `pay_store_notification` | app | StoreNotification |
| media | `media_upload_record` | app | UploadRecord |
| cms | `cms_feedback` | app | Feedback |

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
- **常量**: 放 model class 嵌套 object，跨模块的放 `entity/shared/`
- **编码规则**: 0 保留不用，从 10 开始步长 10

### 枚举码表登记

#### `ImageRef.category`（图片分类，存于 `ai_scan_record.image_keys` JSONB）

| Code | 名称 | 说明 |
|------|------|------|
| 0 | UNSPECIFIED | 未指定/默认（如初次扫描的主图） |
| 10 | FRONT | 正面 |
| 20 | BACK | 背面 |
| 30 | BOTTOM | 底部 / 底面 |
| 40 | MAKER_MARK | 款识 / 签名 |
| 50 | DAMAGE | 损伤 / 磨损 |
| 60 | DIMENSIONS | 尺寸 / 比例（带参照物） |
| 70 | PRICE_TAG | 价签 |
| 80 | DOCUMENTS | 文件 / 来源证明 |
| 90 | DETAIL | 局部细节（通用，可选） |
| 100 | OTHER | 其它 |

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

后端 `AppCrudRepoTemplate.findByOptions` 统一处理，需声明 `filterable` 和 `sortable` 白名单。

## Flyway

- V1–V35, 不可回退
- 迁移文件: `core-api/src/main/resources/db/migration/`
- 最近变更:
  - V32 `ai_scan_deep_research`（深度研究结果表）+ `ai_scan_record.has_deep_search`
  - V33 移除 `ai_scan_record.premium_result`（迁移至 `ai_scan_deep_research`）
  - V34 `ai_scan_record` / `ai_scan_deep_research` 增加 `prompt_version`
  - V35 `ai_scan_record.is_public`（默认 true）

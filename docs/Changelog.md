# Changelog

本文件按时间倒序记录 core-api 面向客户端/数据库的变更。DateTime 用 ISO-8601；数据库变更标注对应 Flyway 版本。

## 2026-09-12

### Changed
- **业务概念 `app` 全量重命名为 `project`**（未上线，不考虑兼容性；顶层包 `com.ifmix.core.api` 与 Spring `Application`/`application.yml` 配置前缀不动）：
  - Header：`x-app-id` → `x-project-id`（`RequestHeaders.PROJECT_ID`）。
  - Kotlin：`appId`→`projectId`、`mustGetAppId`→`mustGetProjectId`、`forApp`→`forProject`、`AppScopedProps`→`ProjectScopedProps`、`BaseAppEntity`→`BaseProjectEntity`、`AppCrudRepoTemplate`→`ProjectCrudRepoTemplate`、`AppConfig*`→`ProjectConfig*`、`AppInfo`→`ProjectInfo`、`AppToIdpRelation`→`ProjectToIdpRelation`；包目录 `modules/app`→`modules/project`、`entity/app`→`entity/project`。
  - DB（改 `V1__baseline.sql` + 重建库）：列 `app_id`→`project_id`；表 `app_config_revision`→`project_config_revision`、`app_info`→`project_info`、`auth_app_to_idp_relation`→`auth_project_to_idp_relation`。
  - 对象存储 key 段 `.../app/{projectId}/...` → `.../project/{projectId}/...`。
  - JWT 仍以 `aud` 承载 projectId（无自定义 claim key，语义不变）。
  - 客户端 `ifmix_apps`：`x-app-id`→`x-project-id`，config `appId`→`projectId`，env `EXPO_PUBLIC_APP_ID`→`EXPO_PUBLIC_PROJECT_ID`。
  - **保留未改**（denylist）：Apple 相关（`apple*`/`AppleConfigValue`/`appAppleId`）、WeChat 凭据字段（`WechatConfigValue.appId`/`appSecret`）、Spring `Application`/`app.*` 配置前缀、legacy `appuser`/`app_user` 表名。

### Changed（续）
- **DB 迁移 squash**：历史 `V1`–`V10` 合并为单个 `V1__baseline.sql`（未上线，不考虑兼容性）。
  baseline 由 `core_api_local`（v10 真实态）`pg_dump --schema-only` 生成，剔除 `flyway_schema_history`；
  已在全新库验证：应用后与原 v10 schema **列/索引零差异**。旧环境需 drop 库后用新 `V1` 重新迁移。

## 2026-09-10

### Changed
- **locale 归一到受支持语言集**：`x-locale` 在 `RequestParser.parseLocale` 入口归一到 10 种受支持语言
  （`en`, `zh-CN`, `zh-TW`, `ja`, `fr`, `es`, `pt`, `de`, `it`, `nl`），不支持/无法解析则视为未提供（`null`，不再抛 `INVALID_REQUEST`）。
  中文按 script/region 分简繁（`zh`/`zh-Hans*`/`zh-SG`/`zh-MY`→`zh-CN`；`zh-TW`/`zh-HK`/`zh-MO`/`zh-Hant*`→`zh-TW`）。
  以后加语言只改 `RequestParser.normalizeLocale`。归一规则见 `docs/ARCHITECTURE.md` 「locale 归一」。

## 2026-09-09

### Added
- **用户支持工单（意见反馈 / 联系我们）**（Flyway `V10`，表 `cs_support_request`）：
  - `m_cs_createSupportRequest` — 创建工单，`status` 固定 `10=OPEN`，各回复/关闭时间戳为 `null`，客户端不可指定；
    身份/`installId`/`locale` 由 header 推导。必填 `title`/`message`/`category`（默认 0）。
  - `q_cs_mySupportRequests` / `q_cs_mySupportRequestById` — 查询本人工单（需 customer token，含匿名 customer；owner-scoped，非本人一律 `NOT_FOUND`）。
  - 码表：`SupportRequestStatuses`（10/20/30/40/50）、`SupportRequestCategories`（0/10/20/30/40/50/100，允许未登记值）。
  - agent 侧流转/回复接口暂未实现，`status` 与各回复时间戳字段已预留。
- **通用 Media 对象**：`MediaInput` / `MediaRef`（`key` + `type` + `category`，`type` 见 `MediaTypes` 码表），
  Kotlin `entity/common/MediaRef` 以 JSONB 存储（如 `cs_support_request.attachments`）。
- **installId 通用化**（`InstallIdProps`）：`x-install-id` header 解析进 `OperationContext.installId`；
  铺到 `ai_scan_record` / `ai_scan_collection` / `cs_feedback` / `cs_support_request`（Flyway `V10` 加 `install_id` 列，可空）。仅记录用于分析，不用于鉴权。
- **scan 批量更新** `m_ai_batchUpdateScan`（当前主要用于批量设置 `collected`）：owner-scoped（按 `appId + customerId + id IN (...)`），返回 `updatedCount`；非本人 id 不计入。
- **scan 创建支持 `collected` 参数**：`NewScanInput.collected`（可空，默认 false），客户端「自动收藏」开关开启时传 `true`。

### Changed
- **DeepResearch 成功判定**：仅当 AI 请求成功 **且** `basic_result.scan_status.status ∈ {SUCCESS, PARTIAL}` 才写回 scan 结果；
  失败（`INSUFFICIENT_IMAGE` / `NON_PHYSICAL_SUBJECT` / 缺失）不覆盖结果，`m_ai_runDeepResearch` 返回 `success=false` + `status` + `scanStatus` 供客户端提示修正。
- **scan / DeepResearch 图片必带 category**：缺省兜底为主图；`ImageCategories.UNSPECIFIED` 更名为 `MAIN`（码值仍为 0，无数据迁移）；`NewScanImageInput` 新增 `category`。

# Changelog

本文件按时间倒序记录 core-api 面向客户端/数据库的变更。DateTime 用 ISO-8601；数据库变更标注对应 Flyway 版本。

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

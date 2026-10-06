# Changelog

本文件按时间倒序记录 core-api 面向客户端/数据库的变更。DateTime 用 ISO-8601；数据库变更标注对应 Flyway 版本。

## 未发布（v1.0.6，2026-10-05 整理）

相对 v1.0.3 的全部变更（feature/install + feature/attest 已合入 main）。发布步骤与决策记录见 `docs/ops/release.md`「v1.0.6 发布计划」。

### Added
- **install 体系**（V5/V6）：`m_install_createInstall` / `m_install_updateInstall`；JWT 新增 `type`（5=install / 10=customer）与 `iid` claim；`core_install` + `core_install_customer_relation`（一 install 一 active customer）。
- **install attestation 一期**（iOS App Attest，V15，默认关）：createInstall 可带 proof；新增 `m_install_createAttestChallenge` / `m_install_recoverInstall` / `m_install_attestExisting`；错误码 403001/403002/409001/404001/429002/503002，429 必带 retryAfterSec。新 env：`APP_ATTEST_GLOBAL_ENABLED`、`APP_ATTEST_CHALLENGE_SECRET`。
- **AI 扫描 / DeepResearch 异步化**（V10–V13）：mutation 秒回 task id，前端轮询状态，完成后 FCM push（`NotificationRequest` / per-project FirebaseAppRegistry / push feature flag）。配额改 `core_ai_customer_scan_metrics` 台账（V4/V9，成功才扣、CAS 原子）；`core_customer` 旧计数列暂留（后续迁移删除）。
- **AI key 池**（V8）：`core_ai_agnes_key` → `core_ai_api_key`，多 key 轮询 + Redis 分布式冷却 + 禁用/probe-skip。
- **wire 加密 v2（强制，无明文降级）**：X25519+HKDF+AES-256-GCM 请求/响应加密，版本头 `x-wirep-version: 2`，>4KB 响应 gzip；`/api/**` 明文请求 400004、解密失败 400003（无版本协商）。ts 偏差仅 warn（客户端时钟偏差不可控，强制时效暂缓——见 wire 设计 §9 决策修订）。请求 body 全局上限 5MB（`WIRE_MAX_REQUEST_BYTES`，超限 400000）。
- **多维限流**：createInstall 入口 100/60s/IP + 验签后 IP 日窗口；createAnonymous / scan / DeepResearch install 层限流；阈值全部 `app.ratelimit.*` 配置、重启生效。
- **`m_customer_deleteAccount`**（V7）：软删 + 解绑全部 install 关系 + 吊销全部 refresh token，事务内原子生效；`DeletionReasons` 码表。
- **`q_ai_scan_getById` 入参新增可选 `include: ["latestDeepResearch"]`**（2026-10-06）：传了才加载 `data.latestDeepResearch`，不传该字段为 `null` 且不产生额外查询。**客户端默认要传**（保持原详情行为）；白名单外值 400000。
- **actionName 修订（feature/graphql-to-rpc，2026-10-06）**：`m_demo_todo_updateOne → m_demo_todo_updateById`、`m_demo_todo_updateItems → m_demo_todoItem_updateMany`（resource 段定 todoItem）；media 资源定名 file：`m_media_media_presignUpload/Download → m_media_file_presignUpload/Download`，表 `core_media_upload_record` → `core_media_file_record`（V16，RENAME 保留数据）。
- **createInstall 按平台拆分**（attest 规格 v6）：`m_auth_install_create` → `m_auth_install_createIosInstall` / `m_auth_install_createAndroidInstall`；自报 `meta.clientPlatform` 与 action 平台不一致（含缺失）→ 400000；proof.provider 与 action 交叉提交 → 400000；两 action 共享同一 IP 限流配额（key 不含平台段）；storeType 交叉校验泛化 110↔10 / 120↔20（只记日志）。限流配额总口径不变。
- **legacy 兼容整体移除**（线上无 app，无需兼容）：`app.auth.legacy-install-id-fallback` 开关、`legacyInstallId` 死路径、legacy 限流计数器（anonymous/scan/DR 的 `legacy-ip-*` 独立阈值）全部删除；无可信 iid 一律 401000，scan/DR 无 iid 直接走大额 IP 层。
- **HTTP RPC + OpenAPI（breaking，feature/graphql-to-rpc）**：GraphQL(DGS) 引擎与 persisted documents 删除，全部 action 迁移为 `POST /api/customer/core/{actionName}`（四段 actionName `{q|m}_{module}_{resource}_{action}`，客户端从 OpenAPI 生成）。请求信封 `{"meta","input"}`（meta=typed `RequestMeta`，凭证暂在 `meta.accessToken`，定稿三段信封顶层 `authorization` 后两端锁步切换）；响应 `Envelope{reqId,code,msg,data}`，HTTP status = code 前三位；错误不再有 partial error。dev 调试通道（local profile）：`Authorization` header + `x-req-meta` header 作 meta 底座（`DevRpcHeaderAdapter`），Swagger UI Authorize 支持。`legacy-install-id-fallback` 随老 app 兼容决策移除（线上无老 app）。

### Changed
- **refresh 契约明确**：`m_auth_refreshToken` 的 Authorization 携带 **customer access token（type=10）**；过期后 refresh 返回 `TOKEN_EXPIRED`，客户端应自动登出（有意设计）。refresh token 暂不校验 `expires_at`（接入第三方登录前，见 `docs/guide/AUTH_DESIGN.md`）。
- **错误透出收紧**：线上（`app.expose-errors=false`）5xx 只返回通用文案，`details`/内部异常信息不再透出。
- **日志 JSON 化**（logstash 一行一条）：MDC 上下文（rid/pid/iid/cid/ip/bot/plat/av/ov/loc/cur/cty）为顶层字段；请求日志含 req/res 摘要；线上看日志需 `jq`。
- **对象存储下载校验收紧**：objectKey 必须内嵌 projectId+actorId 且与 token 身份一致（封顶 24h）。旧 `/project/` 格式 key 无历史数据，无兼容期。
- **refresh token 生成改 `SecureRandom`**（原 `Random` 可预测）；`AUTH_JWT_PRIVATE_KEY` 缺失启动即失败（原静默临时密钥）。
- 旧同步 scan 路径（`m_ai_runAiScan`）废弃，统一走异步任务。

### ~~Fixed~~（2026-10-05 review 声称的修复，2026-10-06 复核**全部未落地**，保持 open——以 release.md「发布前必须完成的修复」为准）
- ~~WireCrypto 低阶点黑名单~~：未修（代码无低阶点校验）。
- ~~ClientIpResolver 可信 IP~~：未修（仍取第一个 XFF，可伪造绕过 IP 限流）。
- ~~日志 refreshToken/authCode 脱敏~~：未修（4xx/5xx WARN 的 req 摘要仍含明文）。
- ~~AI 惰性超时对齐~~：未修（`STALE_IN_PROGRESS_SEC=300` 仍小于 runner 600s 预算，慢任务误杀）。
- ~~DR 空 `premium_result` 判失败~~：未修（AI 正常返回即 SUCCESS，缺 premium_result 不校验）。

### DB 迁移
V4（scan 计数器）→ V5/V6（install）→ V7（deletion）→ V8（key 表改名）→ V9（metrics）→ V10–V13（异步 + 通知）→ V14（project server config）→ V15（attestation）。**V6/V8 要求停机窗口内先迁移后发代码**（旧实例迁移后写入即失败）。

## 2026-09-20

### Added
- **文件日志按级别分文件**（`core-api/src/main/resources/logback-spring.xml`，Spring Boot 自动识别）：`info.log`（DEBUG/INFO）/ `warn.log`（仅 WARN）/ `error.log`（仅 ERROR），按天+50MB 滚动、gzip 归档、留 30 天、3GB 上限、`AsyncAppender` 异步写。控制台仅 `local` profile 输出；目录由 `LOG_PATH` 控制（默认 `./logs`，线上设 `/data/app/log/core-api`）。`logging.level.*` 仍生效。
- **增量部署脚本**（`scripts/deploy/`）：`sync-core-api.sh`（Boot4 `tools extract` 拆 `lib/` + 瘦 jar，`rsync --checksum` 增量同步到双目录 a/b + 软链原子切换 + 健康检查失败自动回切）、`rollback-core-api.sh`（软链切回上一版）、`app-core-api.service`（跑 `core-api-current` 软链下的瘦 jar）。日常发布传输量 142MB→~2.3MB。见 `docs/ops/DEPLOY.md`「进阶（已落地）：增量发布」。
- **`flywayRepair` gradle 任务**（`FlywayRepair`）：重算 `flyway_schema_history` checksum 使之与脚本一致（不改表结构），用于修历史 checksum 漂移。

### Changed
- **DB 全业务表加 `core_` 前缀（V2 迁移）**：21 张业务表 `ALTER TABLE ... RENAME TO core_*`（`core_{module}_{entity}`，不改列/约束/索引名，不动 `flyway_schema_history`）。本机 `core_api_local` 与线上 `app_us1/core_api` 均已应用；物理 FK 自动跟随。两库 flyway 历史对齐（V1=`-1432007747`、V2=`-1291542121`；本地 V1 原 checksum 空，用 `flywayRepair` 修正）。
- **header 格式软校验支持宽松模式**（`RequestParser`）：新增 `app.header-validation.strict` 开关（`APP_HEADER_VALIDATION_STRICT`，默认 `true`）。
  - `strict=true`（测试/开发默认）：`x-locale`/`x-country`/`x-currency` 格式非法 → 抛 `ApiError(INVALID_REQUEST)`，整个请求报错。
  - `strict=false`（线上）：格式非法 → 打 `warn`（`bad header format ignored: ...`）并当作未提供（`null`），请求照常。
  - `required` 缺失、`x-project-id`、token 等硬校验不受开关影响，任何环境都抛。
  - 新增 `parseAppVersion` / `parseBuildVersion` / `parseUpdateVersion`（原样透传，不校验格式——客户端 version 形态多样，如 `x-update-version=1.5`）——此前这些 version header 只记日志；现并入 `ActionContext`（`appVersion`/`buildVersion`/`updateVersion`）。
  - `parseLocale` 细分 `Malformed`（无 language subtag → 走软校验）与 `Unsupported`（合法 BCP 47 但不支持，如 `ko`/`ru` → 任何环境返回 `null` 不抛），内部用 `normalizeLocaleResult`；旧 `normalizeLocale` 保留为薄封装。

### Renamed
- **`Operation` → `Action`**（消除误解：原 `Operation` 指 GraphQL operation 里的**单个 top-level field 的一次执行**，与 GraphQL 规范的 operation 文档冲突）：
  - 类/文件：`OperationContext` → `ActionContext`、`OperationContextProvider` → `ActionContextProvider`、`OperationContextHolder` → `ActionContextHolder`。
  - 字段/变量：`ModuleCtx.op` → `action`、`ActionContext.opName` → `actionName`、`opCtx` 形参/局部 → `actionCtx`。
  - GraphQL 规范术语 `q_/m_` 命名仍称 operation 命名（正确用法，未动）。
- **GraphQL 类型 `OperationResult` → `ActionResult`**：schema（`common.graphqls` 定义 + `auth`/`demo` 引用）改名，与已改好的手写类 `dto.common.ActionResult` 及 codegen `typeMapping` 对齐（Kotlin 侧上一步已改，本步补齐 schema）。

## 2026-09-17

### Changed
- **`projectId` 主键由 UUID 改为 String（slug 即主键，方案 A）**（未上线，不考虑兼容性）：
  - `project_info.id` 即 slug（String 主键），移除原独立 `slug` 列及其唯一索引（唯一性由主键保证）。
  - `ProjectScopedProps.projectId: UUID → String`；`OperationContext`/`ModuleCtx`/`ClusterRouter.forProject` 等全链路改 String。
  - 通用 Repo/Service 主键类型泛型化：`CrudRepoTemplate<E, ID>`、`ProjectCrudRepoTemplate<E, ID>`（projectId 固定 String）、`CrudServiceOps<T, ID>`，含 cursor 泛型化（新增 `parseIdOrNull(idType, raw)`，支持 UUID/String）。现存 UUID 主键表用 `<E, UUID>`，未来 String 主键表用 `<E, String>`。
  - 新增 `StringIdProps`（`@Id val id: String`）作为 String 主键基类，`UUIDProps`/`BaseEntity` 不变。
  - slug 格式受控：`RequestParser.parseProjectId` 用 `^[a-z][a-z0-9-]{2,29}$`（长度 3-30，小写字母开头，含小写字母/数字/连字符），创建后不可变（Firebase project ID 契约）。JWT `aud` 本即 String，去掉 `tryUuid`。
  - 对象存储 key 段 `.../project/{projectId}/...` 直接用 slug（不再 base58）。
  - DB（改 `V1__baseline.sql` + 重建库）：`project_info.id` 与全部业务表 `project_id` `uuid → varchar(30)`（30 与正则最长值对齐，作 DB 层长度防线）；逻辑外键，无物理 FK。

### Changed（续）
- **扫描 prompt 语言规则强化**（`antique-scan-system-basic_v10.md` / `antique-scan-system-deep-research_v10.md`，直接改 v10）：
  - 新增全局约束——凡字段名**不以 `_en` 结尾**必须完全用 `{{RESPONSE_LOCALE}}` 输出、不得混入英文；**以 `_en` 结尾**必须完全英文，明确点名含 `description` 等长文本字段。修复模型偶发把 `description` 输出成英文的问题。
  - basic 补齐**语言判定来源禁令**（原仅 deep-research 有）：不得从 image text（如图中中文款识/铭文）、object origin、`{{MARKET_REGION}}`、`{{VALUATION_CURRENCY}}` 推断语言，语言只由 `{{RESPONSE_LOCALE}}` 决定。修复「传 en 但图含中文时 basic 经常夹中文、deep-research 却正常」的行为差异（两份 prompt 语言判定对齐）。
  - 均为降发生率，非硬保证（LLM 层面无法 100% 消除）。

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
  以后加语言只改 `RequestParser.normalizeLocale`。归一规则见 `docs/guide/ARCHITECTURE.md` 「locale 归一」。

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

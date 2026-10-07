# Release

core-api 发布版本记录（倒序）。版本号即 git tag；「线上」列标当前生产实际运行的版本。

| 版本 | tag commit | 日期 | 线上 | 说明 |
|------|-----------|------|:----:|------|
| v1.0.6 | 未发布 | — |  | install attestation 一期（1a）+ install 体系 + AI 异步/wire 加密 + operationName/reqName 四段式统一（media→file，V16 表改名）/ customer-install 并入 auth / legacy 兼容删除 / createInstall 按平台拆分（见发布计划与 2026-10-06 增量） |
| v1.0.3 | `2a3bbf0` | 2026-09-21 | ✅ | 当前线上版本。**不支持 install**（无 `m_install_*`、token 无 `iid`/`type` claim、`install_id` 为客户端 `x-install-id` 原值） |
| v1.0.2 | `8743e80` | 2026-09-21 |  | R2 objectKey 路径改 `/p/` |

## v1.0.6 发布计划（未发布，原 feature/install + feature/attest 两个分支已合入 main）

### 决策记录（2026-10-05 review 确认）

1. **refresh 的 Authorization 携带 customer access token（type=10），不是 install token**；access token 过期后 refresh 返回 `TOKEN_EXPIRED`，客户端自动登出——有意设计，不改。
2. **refresh token 暂不校验 `expires_at`**（落库 365 天但不校验）：接入 Apple/Google/Email 登录前，过期 = 用户被登出数据全丢，不可接受。详见 `docs/guide/AUTH_DESIGN.md`「Refresh token 有效期」。
3. **core-job 三个 attest job 暂不调度、不清理数据**（长期决策，恢复调度时须先修 P1-attest 三项，见下）。
4. **wire 字节级重放维持现状**（ts 仅 warn）：客户端时钟偏差不可控，不强制时效。详见 wire 设计 §9「决策修订」。
5. **旧 `/project/` 格式 objectKey 无历史数据**，StorageAggHandler 拒签下载无需兼容期。
6. **DeepResearch AI 返回缺 `premium_result` 算失败**：走 key 池重试 N 次（与 scan 同 `attemptCount` 上限），耗尽后任务 FAILED，不允许成功落库 premium=null。详见 DR PG 版设计 §3.1。
7. **发布允许短暂停机 ≤10min**（V6/V8 迁移要求旧实例先停）。
8. **legacy 兼容整体删除（2026-10-06，v1.0.6 未发布无兼容负担）**：`app.auth.legacy-install-id-fallback` 开关 + `parseLegacyInstallId` + `ActionContext.legacyInstallId` 回退、legacy 限流计数器（anonymous/scan/DR 的 legacy-ip-* 独立计数）、DateTime 标量 epoch millis 兼容全部删除。无可信 token iid 的请求一律 401000；logout「缺 iid 只撤销会话」保留为防御分支。**发布不再需要 `APP_LEGACY_INSTALL_ID_FALLBACK` env**。
9. **operationName 与 reqName 四段式统一（2026-10-06）**：39→40 个 GraphQL 顶层 field 全量改名（customer 作用域 CRUD 动词后带 `My`，create 例外；专名动词与 install/session 不加）；persisted query 文本内 operation name == manifest key == 顶层 field；media resource 改 **file**（`m_media_file_presignUpload/Download`，表 `core_media_upload_record` → `core_media_file_record`，V16）。
10. **createInstall 按平台拆分（2026-10-06，attest 规格 v6）**：`m_auth_install_create` → `m_auth_install_createIosInstall` / `m_auth_install_createAndroidInstall`。底层复用；入口强校验 `x-client-platform` 与 action 一致（400000）、proof.provider 与平台匹配（110/120）；限流 key 不含平台段（共享 IP 配额）。attest 仍可选（不带 proof 走 no-proof，ENFORCE 下由 mode 判定）。Android 1b（PlayIntegrityVerifier）实现前：ENFORCE 下 Android proof → 503002。

### 发布前必须完成的修复（2026-10-05 code review，P1）

> **2026-10-06 代码核对**：以下各项（除 attest ×3 外）在当前分支均**未落地**——`ClientIpResolver` 仍取第一个 XFF、`WireCrypto` 无低阶点处理痕迹、`STALE_IN_PROGRESS_SEC` 仍为 300、DR 缺 premium_result 仍直接 SUCCESS、RequestLoggingFilter 错误响应 body 未脱敏 token。Changelog「Fixed」段此前表述与代码不符，已改正。**P1 全部仍为发布前必须完成项。**

- [ ] **P1-attest ×3**（AppleReceiptClientImpl 429 归类 / DeviceCheck receipt 双重 base64 / DeviceCheck JWT 缺 `iat` + teamId 预检）——job 暂不调度，**不阻塞本版**，但列入恢复调度前置条件。
- [ ] **WireCrypto 低阶点黑名单**：当前黑名单常数错误（拦不到真低阶点），安全声明失真，须换真实编码或如实注释。
- [ ] **ClientIpResolver 可信 IP**：XFF 首段可伪造导致全部 `:ip:` 限流可绕过，改取 `CF-Connecting-IP`/可信代理链。
- [ ] **日志脱敏**：RequestLoggingFilter 4xx/5xx WARN 输出 refreshToken/authCode 明文；Google webhook token 经 query 进日志。
- [ ] **AI 惰性超时与预算对齐**：`STALE_IN_PROGRESS_SEC=300` < AI 调用 360s < runner 600s，慢而成功的任务被误杀。
- [ ] **DR 空 premium_result 判失败**（决策 6 落地）。

应修（P2）：RateLimiter INCR/EXPIRE 原子化、bind 关系部分唯一索引、logout 只认可信 iid、webhook 幂等唯一索引、FirebaseAppRegistry 负缓存、AiChatClientFactory 无淘汰缓存、scan 表 customer_id 索引、attestExisting 冲突重查返回值。~~ActionContextProvider 与 yml 默认值对齐~~（随 legacy 删除消解）。

### 发布步骤（停机 ≤10min）

1. 完成上述修复，全量 `./gradlew :core-api:test :core-job:test` 通过；本地 `:core-api:flywayMigrate` 从零库验证。
2. **停服**（旧实例停止，≤10min 窗口）。
3. `./gradlew :core-api:flywayMigrate`（V4–V17）。**V6 改 `install_id` 列类型（text→uuid）、V8 改 AI key 表名——迁移后旧代码写入即失败，必须先停机再迁移**（决策 7）。
4. 部署新 core-api（增量脚本 `scripts/deploy/sync-core-api.sh` + 健康检查），恢复流量。
5. core-job 同步部署；三个 attest job **不配 cron**（决策 3）。
6. env 核对：`AUTH_JWT_PRIVATE_KEY`（缺失启动即失败）、`APP_ATTEST_GLOBAL_ENABLED`（默认 false）、`APP_ATTEST_CHALLENGE_SECRET`。
7. 灰度：服务端上线（attest 全局关、无 project 配置 → 现网零影响）→ 客户端发布 → `APP_ATTEST_GLOBAL_ENABLED=true` + project 写 `app_attest_config`（mode=OBSERVE）→ 观察指标 → 切 ENFORCE（前置检查见下方 feature/attest 小节）。

## v1.0.6 增量（2026-10-06：RPC 试点、命名统一与模块结构调整）

本组变更全部为客户端 breaking，需与客户端发布节奏协同：

- **GraphQL operationName 与 reqName 统一四段式**：39 个顶层 field（`@DgsQuery/@DgsMutation`）全量改名（customer 作用域 CRUD 动词后带 `My`，create 例外；专名动词与 install/session 不加）；persisted query 文本内 operation name == manifest key == 顶层 field name。39 条全量名单即 `persisted-queries/customer/customer.json` 的 key。
- **media resource 改名 file**：`m_media_media_*` → `m_media_file_presignUpload` / `m_media_file_presignDownload`；表 `core_media_upload_record` → `core_media_file_record`（V16，纯 RENAME，已在本地库验证）。
- **createInstall 按平台拆分**（attest 规格 v6）：`m_auth_install_create` → `m_auth_install_createIosInstall` / `m_auth_install_createAndroidInstall`（决策 10）；客户端 SDK 按 `platform` 选 action，manifest 现 40 条。
- **legacy 兼容整体删除**（决策 8）：fallback 开关链路 + legacy 限流计数器 + DateTime epoch millis。发布 env 不再需要 `APP_LEGACY_INSTALL_ID_FALLBACK`。
- **customer/install 并入 auth 模块**：服务端内部结构（`modules/auth/{install,customer}`、`entity/auth/`）+ reqName namespace 变化（`m_auth_install_*`、`m_auth_customer_*`）。
- **RPC URL 定稿 `POST /customer/core/greq/{reqName}`**：原 `/api/customer/core` 方案废弃；客户端 R0 已实现的 `/api/customer/core` URL 需在联调前同步调整。

### v1.0.6 增量（2026-10-07：demo E2E 走查修复）

- **V17**：`core_demo_todo_item` 补 `note` 列（实体/schema 早已有，建表迁移遗漏）。
- 跨线程 ActionContext 传播改权威通道 `RequestActionContext`（DGS custom context，随 DgsContext 传递）：嵌套 resolver 复用顶层原 ctx（否则重建会把 mutation 内的读判成 preferReader=true 误走 reader 池）；4 个 DataLoader 改 `MappedBatchLoaderWithContext`。ThreadLocal 机制整体移除（`ActionContextHolder`/`ProjectScopedFilter.kt` 删除）。
- 新增 demo E2E 脚本 `scripts/demo-e2e.mjs`（19 步全链路，19/19 通过；`:core-api:test :core-job:test` 全过）。
- attest 无需新增绕过开关：`APP_ATTEST_GLOBAL_ENABLED` 默认 false 即全绕过（曾临时加 `APP_ATTEST_BYPASS`，与关掉全局开关无实质差异，已删除）。

### v1.0.6 风险与待办（2026-10-06 梳理）

- **[迁移] V10 checksum 不匹配**：V10 迁移文件在部分环境应用后曾被修改；本地库已按 AGENTS.md 流程 `flywayRepair` + `flywayMigrate`（V16）验证通过。**其他已跑过 V10 的环境（uat/prod）发布前会同样 validate 失败，需先 `flywayRepair`**——确认 V10 的当前内容即为期望内容后再 repair。
- **[迁移] V17 `SET NOT NULL` 收尾待建**：业务表 `install_id` 列仍 nullable（V6 只做类型转型；「V7 收尾」从未创建，V7 已被删除功能占用）。legacy 回填前提已随 legacy 删除消解，但线上 v1.0.3 存量行可能为 null，需先核对回填可行性再建迁移。
- **[attest] 剩余人工项**（设计文档 §9/§10）：真机 fixture smoke（§10.1）、TestFlight 冒烟（§9，production verifier 跑通 create + recover）、Apple 端点假设实测（§10.4，core-job 两个 Impl）；core-job 三任务继续停调度（决策 3）。
- **[attest] Android 1b 未实现**：`createAndroidInstall` 已就位，proof 120 在 ENFORCE 下 503002 / OBSERVE 放行；PlayIntegrityVerifier 与客户端 Play Integrity 接入在 1b（Android 客户端发布前）。
- **[核对] Changelog「Fixed」段已修正**：2026-10-05 review 的 P1 五项当时并未落地，本清单为准。

## v1.0.6 增量（`feature/attest`）

install attestation 一期（1a，iOS App Attest）。设计见 `docs/design/attest/install-attestation.md`（注意 §0 v5 修订表）。相对 `feature/install` 的主要变化：

- **attestation（默认关）**：`m_auth_install_create` 新增 `proof` / `proofStatus` / `storeType` 入参 + `attestationStatus` 返回（10/20/30）；新增 3 个 mutation `m_auth_install_createAttestChallenge` / `m_auth_install_recover` / `m_auth_install_attest`（存量补证）。判定矩阵 / 错误码（403001/403002/409001/404001/429002/503002）/ retryAfterSec extensions（429000/429002 必带）见规格 §4.3/§4.4。
- **限流阈值调整**：createInstall 入口 10/60s → **100/60s/IP**，验签后新增 IP 日窗口（attested 1000/天、unverified 100/天，UTC 日分桶）；下游 createAnonymous / scan / DeepResearch 增加 install 层限流（防滥用，小阈值）+ 上调 IP 层（系统防护，100/min + 1000/天），legacy 请求走独立旧严格阈值计数器；阈值全部 `app.ratelimit.*` 配置、**重启生效**（紧急降额需重启/发布，非即时 kill switch）。上线前需按规格 §4.6 核对 AI key 池容量。
- **新 env 两个**：`APP_ATTEST_GLOBAL_ENABLED`（默认 false，全局 kill switch）、`APP_ATTEST_CHALLENGE_SECRET`（`current[,previous]`，32 字节 base64；全局开关开时缺失 = 配置无效 fail-closed → 503002 + 节流日志 `attest.config_invalid`）。
- **DB 迁移 V14/V15**：`core_project_server_config.app_attest_config`（JSONB）；`core_install.store_type`（INT NULL）；新表 `core_install_attestation`（含 `attestation_object` 回填列）。先 `flywayMigrate` 再发新代码。
- **core-job 三个新任务**（`--job.name`）：`attestReceiptBackfill`（Apple receipt 回填，attestation_object 换 receipt）、`attestFraudMetricRefresh`（DeviceCheck two bits，失败指数退避封顶 24h；deviceCheck 配置缺失跳过不报错）、`attestEvidenceCleanup`（evidence 90 天清理）。外部 cron 触发，频率见规格 §5.7/§5.8（backfill/refresh 建议每日，evidence 每日）。**【2026-10-05 运维决定】执行入口已注释（`AttestJobs` 三个 @Bean），线上暂不调度、不配 cron**——不影响 createInstall/recover/attestExisting（纯本地验证，不依赖 receipt/fraud_metric）；代价 = fraud_metric 缺失（二期策略需要时再恢复）+ `attestation_object` 列随 install 增长。恢复：取消 `AttestJobs.kt` 内注释 → 部署 → 配 cron（首次启用会回填全部历史行，注意对 Apple 的集中请求量）。
- **发布顺序**：服务端（含 schema + allowlist）先上线（全局开关关、无 project 配置 → 现网零影响），客户端再发布（flag 默认关）；随后 `APP_ATTEST_GLOBAL_ENABLED=true` + project 写 `app_attest_config`（mode=OBSERVE 只配 ios）→ 观察灰度指标（规格 §8）→ 满足 §4.5 后切 ENFORCE。
  - 上线前检查清单（规格 §9，不可跳过）：App ID 开 App Attest capability + 两套 profile；Archive `.app` codesign 确认 entitlement 值；TestFlight 真机冒烟（production verifier 跑通 create + recover）；平台发布清单（`app.config.js` 声明 platforms 含 android、EAS 有 android profile 时，1b 完成前**禁止切 ENFORCE**）。

## v1.0.6 增量（`feature/install`）

相对 v1.0.3 的主要变化，发布前逐项确认：

- **install 体系**：`m_auth_install_create` / `m_auth_install_updateOne`；token 增加 `type`（5=install / 10=customer）与 `iid` claim；`core_install` + `core_install_customer_relation`（V5/V6）。
- ~~**老 app 兼容**：`app.auth.legacy-install-id-fallback`~~ **2026-10-06 已删除**（决策 8）：v1.0.6 未发布，无兼容负担；installId 一律取 token 可信 iid。
- **扫描计数迁表**：`core_customer.scan_count / deep_research_count` → `core_ai_customer_scan_metrics`（V9，旧列暂留，发布完成后另起迁移删除）。
- **AI key 池**：轮询 + Redis 分布式冷却；`core_ai_agnes_key` → `core_ai_api_key`（V8）。
- **错误透出**：线上（`app.expose-errors=false`）5xx 只返回通用文案。
- **日志**：文件日志改为 JSON（logstash 格式，一行一条）；MDC 上下文字段 `rid pid iid cid ip bot plat av ov loc cur cty` 为顶层字段；请求日志 `method/path/httpStatus/duration(ms 数值)/req/res`；请求头/响应头 `x-req-id`。线上看日志需 `jq`，日志采集侧按 JSON 解析。

发布注意：
- **DB 迁移顺序**：先 `flywayMigrate`（V4–V9）再发新代码；V6 改 `install_id` 列类型（text→uuid），旧代码在迁移后写入会失败，需短暂停机或先停旧实例。
- `AUTH_JWT_PRIVATE_KEY` 必须配置（缺失启动即失败）。

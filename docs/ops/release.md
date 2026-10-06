# Release

core-api 发布版本记录（倒序）。版本号即 git tag；「线上」列标当前生产实际运行的版本。

| 版本 | tag commit | 日期 | 线上 | 说明 |
|------|-----------|------|:----:|------|
| v1.0.6 | 未发布 | — |  | install attestation 一期（1a）+ install 体系 + AI 异步/wire 加密等（见发布计划） |
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

### 发布前必须完成的修复（2026-10-05 code review，P1）

- [ ] **P1-attest ×3**（AppleReceiptClientImpl 429 归类 / DeviceCheck receipt 双重 base64 / DeviceCheck JWT 缺 `iat` + teamId 预检）——job 暂不调度，**不阻塞本版**，但列入恢复调度前置条件。
- [x] **WireCrypto 低阶点黑名单**：已随 HPKE 迁移消解（2026-10-06 复核）——手写 X25519/HKDF 与黑名单常数已不存在，现实现为 BouncyCastle `org.bouncycastle.crypto.hpke`（wire-encryption.md §10.3「低阶点等输入校验全部交给 HPKE 库」）。**收尾动作**：安全复核确认 BC 对低阶点/全零共享密钥的拒绝行为后正式关闭本项。
- [ ] **ClientIpResolver 可信 IP**：XFF 首段可伪造导致全部 `:ip:` 限流可绕过，改取 `CF-Connecting-IP`/可信代理链（2026-10-06 复核：仍未修）。
- [ ] **日志脱敏**：RequestLoggingFilter 4xx/5xx WARN 输出 refreshToken/authCode 明文；Google webhook token 经 query 进日志（2026-10-06 复核：仍未修）。
- [ ] **AI 惰性超时与预算对齐**：`STALE_IN_PROGRESS_SEC=300` < runner 600s 总预算（2026-10-06 复核：仍未修，300–600s 的慢任务会被查询侧误杀 TIMEOUT）。
- [ ] **DR 空 premium_result 判失败**（决策 6 落地；2026-10-06 复核：仍未修，AI 正常返回即 SUCCESS）。

应修（P2）：RateLimiter INCR/EXPIRE 原子化、bind 关系部分唯一索引、logout 只认可信 iid、webhook 幂等唯一索引、FirebaseAppRegistry 负缓存、AiChatClientFactory 无淘汰缓存、scan 表 customer_id 索引、attestExisting 冲突重查返回值、ActionContextProvider 与 yml 默认值对齐。全量清单见 review 记录；修复用提示词 A–E 派发。

### 发布步骤（停机 ≤10min）

1. 完成上述修复，全量 `./gradlew :core-api:test :core-job:test` 通过；本地 `:core-api:flywayMigrate` 从零库验证。
2. **停服**（旧实例停止，≤10min 窗口）。
3. `./gradlew :core-api:flywayMigrate`（V4–V15/16，以发布时实际为准）。**V6 改 `install_id` 列类型（text→uuid）、V8 改 AI key 表名——迁移后旧代码写入即失败，必须先停机再迁移**（决策 7）。
4. 部署新 core-api（增量脚本 `scripts/deploy/sync-core-api.sh` + 健康检查），恢复流量。
5. core-job 同步部署；三个 attest job **不配 cron**（决策 3）。
6. env 核对：`AUTH_JWT_PRIVATE_KEY`（缺失启动即失败）、`APP_ATTEST_GLOBAL_ENABLED`（默认 false）、`APP_ATTEST_CHALLENGE_SECRET`。
7. 灰度：服务端上线（attest 全局关、无 project 配置 → 现网零影响）→ 客户端发布 → `APP_ATTEST_GLOBAL_ENABLED=true` + project 写 `app_attest_config`（mode=OBSERVE）→ 观察指标 → 切 ENFORCE（前置检查见下方 feature/attest 小节）。

## v1.0.6 增量（`feature/attest`）

install attestation 一期（1a，iOS App Attest）。设计见 `docs/design/attest/install-attestation.md`（注意 §0 v5 修订表）。相对 `feature/install` 的主要变化：

- **attestation（默认关）**：`m_install_createInstall` 新增 `proof` / `proofStatus` / `storeType` 入参 + `attestationStatus` 返回（10/20/30）；新增 3 个 mutation `m_install_createAttestChallenge` / `m_install_recoverInstall` / `m_install_attestExisting`（存量补证）。判定矩阵 / 错误码（403001/403002/409001/404001/429002/503002）/ retryAfterSec extensions（429000/429002 必带）见规格 §4.3/§4.4。
- **限流阈值调整**：createInstall 入口 10/60s → **100/60s/IP**，验签后新增 IP 日窗口（attested 1000/天、unverified 100/天，UTC 日分桶）；下游 createAnonymous / scan / DeepResearch 增加 install 层限流（防滥用，小阈值）+ 上调 IP 层（系统防护，100/min + 1000/天）。legacy 严格阈值计数器已随 legacy 兼容移除（2026-10-06，线上无 app）；阈值全部 `app.ratelimit.*` 配置、**重启生效**（紧急降额需重启/发布，非即时 kill switch）。上线前需按规格 §4.6 核对 AI key 池容量。
- **新 env 两个**：`APP_ATTEST_GLOBAL_ENABLED`（默认 false，全局 kill switch）、`APP_ATTEST_CHALLENGE_SECRET`（`current[,previous]`，32 字节 base64；全局开关开时缺失 = 配置无效 fail-closed → 503002 + 节流日志 `attest.config_invalid`）。
- **DB 迁移 V14/V15**：`core_project_server_config.app_attest_config`（JSONB）；`core_install.store_type`（INT NULL）；新表 `core_install_attestation`（含 `attestation_object` 回填列）。先 `flywayMigrate` 再发新代码。
- **core-job 三个新任务**（`--job.name`）：`attestReceiptBackfill`（Apple receipt 回填，attestation_object 换 receipt）、`attestFraudMetricRefresh`（DeviceCheck two bits，失败指数退避封顶 24h；deviceCheck 配置缺失跳过不报错）、`attestEvidenceCleanup`（evidence 90 天清理）。外部 cron 触发，频率见规格 §5.7/§5.8（backfill/refresh 建议每日，evidence 每日）。**【2026-10-05 运维决定】执行入口已注释（`AttestJobs` 三个 @Bean），线上暂不调度、不配 cron**——不影响 createInstall/recover/attestExisting（纯本地验证，不依赖 receipt/fraud_metric）；代价 = fraud_metric 缺失（二期策略需要时再恢复）+ `attestation_object` 列随 install 增长。恢复：取消 `AttestJobs.kt` 内注释 → 部署 → 配 cron（首次启用会回填全部历史行，注意对 Apple 的集中请求量）。
- **发布顺序**：服务端（含 schema + allowlist）先上线（全局开关关、无 project 配置 → 现网零影响），客户端再发布（flag 默认关）；随后 `APP_ATTEST_GLOBAL_ENABLED=true` + project 写 `app_attest_config`（mode=OBSERVE 只配 ios）→ 观察灰度指标（规格 §8）→ 满足 §4.5 后切 ENFORCE。
  - 上线前检查清单（规格 §9，不可跳过）：App ID 开 App Attest capability + 两套 profile；Archive `.app` codesign 确认 entitlement 值；TestFlight 真机冒烟（production verifier 跑通 create + recover）；平台发布清单（`app.config.js` 声明 platforms 含 android、EAS 有 android profile 时，1b 完成前**禁止切 ENFORCE**）。

## v1.0.6 增量（`feature/install`）

相对 v1.0.3 的主要变化，发布前逐项确认：

- **install 体系**：`m_install_createInstall` / `m_install_updateInstall`；token 增加 `type`（5=install / 10=customer）与 `iid` claim；`core_install` + `core_install_customer_relation`（V5/V6）。
- ~~老 app 兼容~~：`app.auth.legacy-install-id-fallback` 已整体移除（2026-10-06 决策：线上无 app，无需兼容）——token 必须携带可信 `iid`，否则 401000。
- **扫描计数迁表**：`core_customer.scan_count / deep_research_count` → `core_ai_customer_scan_metrics`（V9，旧列暂留，发布完成后另起迁移删除）。
- **AI key 池**：轮询 + Redis 分布式冷却；`core_ai_agnes_key` → `core_ai_api_key`（V8）。
- **错误透出**：线上（`app.expose-errors=false`）5xx 只返回通用文案。
- **日志**：文件日志改为 JSON（logstash 格式，一行一条）；MDC 上下文字段 `rid pid iid cid ip bot plat av ov loc cur cty` 为顶层字段；请求日志 `method/path/httpStatus/duration(ms 数值)/req/res`；请求头/响应头 `x-req-id`。线上看日志需 `jq`，日志采集侧按 JSON 解析。

发布注意：
- **DB 迁移顺序**：先 `flywayMigrate`（V4–V9）再发新代码；V6 改 `install_id` 列类型（text→uuid），旧代码在迁移后写入会失败，需短暂停机或先停旧实例。
- `AUTH_JWT_PRIVATE_KEY` 必须配置（缺失启动即失败）。

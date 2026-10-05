# Release

core-api 发布版本记录（倒序）。版本号即 git tag；「线上」列标当前生产实际运行的版本。

| 版本 | tag commit | 日期 | 线上 | 说明 |
|------|-----------|------|:----:|------|
| 未发布 | `feature/attest` | — |  | install attestation 一期（1a，见下） |
| 未发布 | `feature/install` | — |  | install 体系（见下） |
| v1.0.3 | `2a3bbf0` | 2026-09-21 | ✅ | 当前线上版本。**不支持 install**（无 `m_install_*`、token 无 `iid`/`type` claim、`install_id` 为客户端 `x-install-id` 原值） |
| v1.0.2 | `8743e80` | 2026-09-21 |  | R2 objectKey 路径改 `/p/` |

## 未发布（`feature/attest`）

install attestation 一期（1a，iOS App Attest）。设计见 `docs/superpowers/specs/2026-10-04-install-attestation-design.md`（注意 §0 v5 修订表）。相对 `feature/install` 的主要变化：

- **attestation（默认关）**：`m_install_createInstall` 新增 `proof` / `proofStatus` / `storeType` 入参 + `attestationStatus` 返回（10/20/30）；新增 3 个 mutation `m_install_createAttestChallenge` / `m_install_recoverInstall` / `m_install_attestExisting`（存量补证）。判定矩阵 / 错误码（403001/403002/409001/404001/429002/503002）/ retryAfterSec extensions（429000/429002 必带）见规格 §4.3/§4.4。
- **限流阈值调整**：createInstall 入口 10/60s → **100/60s/IP**，验签后新增 IP 日窗口（attested 1000/天、unverified 100/天，UTC 日分桶）；下游 createAnonymous / scan / DeepResearch 增加 install 层限流（防滥用，小阈值）+ 上调 IP 层（系统防护，100/min + 1000/天），legacy 请求走独立旧严格阈值计数器；阈值全部 `app.ratelimit.*` 配置、**重启生效**（紧急降额需重启/发布，非即时 kill switch）。上线前需按规格 §4.6 核对 AI key 池容量。
- **新 env 两个**：`APP_ATTEST_GLOBAL_ENABLED`（默认 false，全局 kill switch）、`APP_ATTEST_CHALLENGE_SECRET`（`current[,previous]`，32 字节 base64；全局开关开时缺失 = 配置无效 fail-closed → 503002 + 节流日志 `attest.config_invalid`）。
- **DB 迁移 V14/V15**：`core_project_server_config.app_attest_config`（JSONB）；`core_install.store_type`（INT NULL）；新表 `core_install_attestation`（含 `attestation_object` 回填列）。先 `flywayMigrate` 再发新代码。
- **core-job 三个新任务**（`--job.name`）：`attestReceiptBackfill`（Apple receipt 回填，attestation_object 换 receipt）、`attestFraudMetricRefresh`（DeviceCheck two bits，失败指数退避封顶 24h；deviceCheck 配置缺失跳过不报错）、`attestEvidenceCleanup`（evidence 90 天清理）。外部 cron 触发，频率见规格 §5.7/§5.8（backfill/refresh 建议每日，evidence 每日）。
- **发布顺序**：服务端（含 schema + allowlist）先上线（全局开关关、无 project 配置 → 现网零影响），客户端再发布（flag 默认关）；随后 `APP_ATTEST_GLOBAL_ENABLED=true` + project 写 `app_attest_config`（mode=OBSERVE 只配 ios）→ 观察灰度指标（规格 §8）→ 满足 §4.5 后切 ENFORCE。
  - 上线前检查清单（规格 §9，不可跳过）：App ID 开 App Attest capability + 两套 profile；Archive `.app` codesign 确认 entitlement 值；TestFlight 真机冒烟（production verifier 跑通 create + recover）；平台发布清单（`app.config.js` 声明 platforms 含 android、EAS 有 android profile 时，1b 完成前**禁止切 ENFORCE**）。

## 未发布（`feature/install`）

相对 v1.0.3 的主要变化，发布前逐项确认：

- **install 体系**：`m_install_createInstall` / `m_install_updateInstall`；token 增加 `type`（5=install / 10=customer）与 `iid` claim；`core_install` + `core_install_customer_relation`（V5/V6）。
- **老 app 兼容**：`app.auth.legacy-install-id-fallback`（默认 `true`）——token 无 `iid` 时 createAnonymous / login / refresh / 写入退回 `x-install-id` header。老 app 全部升级后关闭。
- **扫描计数迁表**：`core_customer.scan_count / deep_research_count` → `core_ai_customer_scan_metrics`（V9，旧列暂留，发布完成后另起迁移删除）。
- **AI key 池**：轮询 + Redis 分布式冷却；`core_ai_agnes_key` → `core_ai_api_key`（V8）。
- **错误透出**：线上（`app.expose-errors=false`）5xx 只返回通用文案。
- **日志**：文件日志改为 JSON（logstash 格式，一行一条）；MDC 上下文字段 `rid pid iid cid ip bot plat av ov loc cur cty` 为顶层字段；请求日志 `method/path/httpStatus/duration(ms 数值)/req/res`；请求头/响应头 `x-req-id`。线上看日志需 `jq`，日志采集侧按 JSON 解析。

发布注意：
- **DB 迁移顺序**：先 `flywayMigrate`（V4–V9）再发新代码；V6 改 `install_id` 列类型（text→uuid），旧代码在迁移后写入会失败，需短暂停机或先停旧实例。
- `AUTH_JWT_PRIVATE_KEY` 必须配置（缺失启动即失败）。

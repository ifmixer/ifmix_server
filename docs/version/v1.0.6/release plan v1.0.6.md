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
9. **operationName 与 reqName 四段式统一（2026-10-06）**：39→40 个 GraphQL 顶层 field 全量改名（customer 作用域 CRUD 动词后带 `My`，create 例外；专名动词与 install/session 不加）；persisted query 文本内 operation name == manifest key == 顶层 field；media resource 改 **file**（`m_media_file_presignUpload/Download`，表 `core_media_upload_record` → `core_media_filerecord`，V16）。
10. **createInstall 按平台拆分（2026-10-06，attest 规格 v6）**：`m_auth_install_create` → `m_auth_install_createIosInstall` / `m_auth_install_createAndroidInstall`。底层复用；入口强校验 `x-client-platform` 与 action 一致（400000）、proof.provider 与平台匹配（110/120）；限流 key 不含平台段（共享 IP 配额）。attest 仍可选（不带 proof 走 no-proof，ENFORCE 下由 mode 判定）。Android 1b（PlayIntegrityVerifier）实现前：ENFORCE 下 Android proof → 503002。
11. **challenge secret 改 per-project（2026-10-07）**：`app_attest_config.challengeSecret`（`current[,previous]`，32 字节 base64）每 app 独立，优先于 env `APP_ATTEST_CHALLENGE_SECRET`（env 保留为回落，本地 dev/过渡期用）；per-project 格式非法记 problems（ENFORCE fail-closed）。**每个 app 上线前必须各自生成独立 secret 写入 DB**；决策依据与「iOS/Android 不拆分」的分析见设计 §3.1。
12. **customer/install 表挂 auth 域前缀（2026-10-07，V18）**：`core_customer` → `core_auth_customer`、`core_install` → `core_auth_install`、`core_install_customer_relation` → `core_auth_install2customer`、`core_install_attestation` → `core_auth_installattestation`（attest 凭证/绑定表随 install 走）。纯改名（无物理外键、索引名不变）；历史增量段落中的旧表名以 V18 为准。
13. **attestation 表改「带 proof 必留痕」（2026-10-07，V19）**：`core_auth_installattestation` 增 `verify_status`（10 VALID / 20 INVALID / 30 NOT_EVALUATED，可空、无默认值）与 `challenge`；INVALID/NOT_EVALUATED 行 status=NOT_BOUND(40) 留痕，原始 attestation 字节与 challenge 全保留（后端算法修正后可离线重验）；唯一索引只约束 VALID 行；绑定语义查询（findBySubject/轮换/recover）只认 verify_status=10。证明材料上限 16KB → 1MB。设计 §5.4 已同步。
14. **表名去重（2026-10-07，V20）**：域前缀（`core_<域>_`）保留层级作用，域内单词不再用 `_` 连接（如 `core_ai_scan_record` → `core_ai_scanrecord`）；关系表用 `2`（=to）连接（`core_auth_install2customer` / `core_auth_identity2idp` / `core_auth_project2idp`）；两张长名简化（`core_ai_customer_scan_metrics` → `core_ai_scanmetrics`、`core_ai_scan_deep_research` → `core_ai_deepresearch`）。列名不动（`_` 的层级作用只限表名）。曾短暂改 `core_org_project*`，最终保留 `core_project_*`（org 不是系统实体，词汇对齐 project）。
15. **迁移历史收敛为单一 V1（2026-10-07）**：历史 V1–V20 按最终 schema 状态固化为 `V1__init.sql`（pg_dump 快照去除 psql 元命令），旧迁移文件删除；线上初始化 = 空库 `flywayMigrate` 应用 V1 + 导入种子数据（`scripts/deploy/db-init/`，含 Flyway 历史后无需 baseline）。后续变更从 V2 递增。

### 发布前必须完成的修复（2026-10-05 code review，P1）

> **2026-10-06 代码核对**：以下各项（除 attest ×3 外）在当前分支均**未落地**——`ClientIpResolver` 仍取第一个 XFF、`WireCrypto` 无低阶点处理痕迹、`STALE_IN_PROGRESS_SEC` 仍为 300、DR 缺 premium_result 仍直接 SUCCESS、RequestLoggingFilter 错误响应 body 未脱敏 token。Changelog「Fixed」段此前表述与代码不符，已改正。**P1 全部仍为发布前必须完成项。**

- [ ] **P1-attest ×3**（AppleReceiptClientImpl 429 归类 / DeviceCheck receipt 双重 base64 / DeviceCheck JWT 缺 `iat` + teamId 预检）——job 暂不调度，**不阻塞本版**，但列入恢复调度前置条件。
- [ ] **WireCrypto 低阶点黑名单**：当前黑名单常数错误（拦不到真低阶点），安全声明失真，须换真实编码或如实注释。
- [ ] **ClientIpResolver 可信 IP**：XFF 首段可伪造导致全部 `:ip:` 限流可绕过，改取 `CF-Connecting-IP`/可信代理链。
- [ ] **日志脱敏**：RequestLoggingFilter 4xx/5xx WARN 输出 refreshToken/authCode 明文；Google webhook token 经 query 进日志。
- [ ] **AI 惰性超时与预算对齐**：`STALE_IN_PROGRESS_SEC=300` < AI 调用 360s < runner 600s，慢而成功的任务被误杀。
- [ ] **DR 空 premium_result 判失败**（决策 6 落地）。

应修（P2）：RateLimiter INCR/EXPIRE 原子化、bind 关系部分唯一索引、logout 只认可信 iid、webhook 幂等唯一索引、FirebaseAppRegistry 负缓存、AiChatClientFactory 无淘汰缓存、scan 表 customer_id 索引、attestExisting 冲突重查返回值。~~ActionContextProvider 与 yml 默认值对齐~~（随 legacy 删除消解）。

### 发布 Runbook（首版 1.0.6，可执行清单）

#### Phase 0 — 发布前必须完成（本地）

- [ ] **P1 五项修复**（上方清单，全部打勾）→ 全量 `./gradlew :core-api:test :core-job:test` 通过（仅 Docker 环境用例可跳过）
- [ ] 本地从零库验证：`DROP SCHEMA public CASCADE; CREATE SCHEMA public;` → `./gradlew :core-api:flywayMigrate` → 恢复种子数据 → bootRun 冒烟（已验证过一次，重构 schema 后需重验）
- [ ] `.env.prod` 终审（见 Phase 1 清单），`data_seed.sql` 确认不在 git 暂存区

#### Phase 1 — 服务器环境配置（`/data/app/core-api/common/.env.prod`，经 `scripts/deploy/push-env.sh` 上传）

| 变量 | 值 | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` / `PORT` / `LOG_PATH` | `prod` / `3001` / `/data/app/log/core-api` | 已有 |
| `PG_WRITER_URL` / `PG_READER_URL` / `PG_USERNAME` / `PG_PASSWORD` | 线上库 | 已有 |
| `REDIS_URL` | 线上 Redis | 已有 |
| `STORAGE_*` | **⚠️ 换生产桶/密钥**（当前是 dev R2，文件里自带 TODO） | 待办 |
| `SPRING_AI_OPENAI_BASE_URL` / `MODEL` | agnes 线上地址 | key 走 DB（core_ai_apikey 种子） |
| `AUTH_JWT_PRIVATE_KEY` / `AUTH_ACCESS_TTL_SEC` | Ed25519 JWK 私钥（**上线定稿，之后换 key = 全员登出**） | 已有，确认定稿 |
| `WIRE_CRYPTO_KEYS` | `1:<私钥base64>`（**无引号**；客户端 env.ts 填配对 pubHex，kid=1） | 已有，核对配对 |
| `APP_ATTEST_GLOBAL_ENABLED` | `false`（首版先关，灰度第 3 步再改 true + 重启） | 已有=true，**改成 false** 或接受上线即 OBSERVE 记录 |
| `GOOGLE_WEBHOOK_TOKEN` | 自生成随机值，须与 Google Play Console 后台一致 | **待填** |
| `APP_ATTEST_CHALLENGE_SECRET` | 不配（per-project secret 已在 seed 里；此项仅作回落） | — |
| `APP_DATASOURCE_BUSINESS_*` / `APP_DATASOURCE_JOB_*` | core-job 用（宽松绑定覆盖硬编码） | 已有 |

上传：`scripts/deploy/push-env.sh`（远端自动备份 `.bak.<ts>`、权限 640 root:app）→ `systemctl daemon-reload`。
验证：`systemctl show app-core-api -p Environment | tr ' ' '\n' | grep -E 'WIRE|ATTEST|WEBHOOK'`。

#### Phase 2 — 线上 DB 初始化（空库；服务器无需 gradle/源码，走 SSH 隧道从本地执行）

```bash
# 1) 隧道（本地 15432 → 线上 5432；线上 PG 只听 localhost）
ssh -N -L 15432:localhost:5432 app_us1

# 2) 本地另开终端：flyway 应用 V1（连接覆盖见 build.gradle.kts flywayMigrate 注释）
DB_URL=jdbc:postgresql://localhost:15432/core_api \
DB_USER=app DB_PASSWORD=<线上密码> \
./gradlew :core-api:flywayMigrate        # 26 张表 + flyway_schema_history（含 V1 正确 checksum）

# 3) 种子数据（3 张表，含线上 challengeSecret/env=production），同样走隧道
psql "postgresql://app:<线上密码>@localhost:15432/core_api" -f scripts/deploy/db-init/data_seed.sql
```

核对：`SELECT count(*) FROM core_ai_apikey;`（3169）、`flyway_schema_history` 仅 `1|init`。
后续 V2+ 发布用同一隧道命令，无需 baseline。

#### Phase 3 — 部署

1. core-api：`scripts/deploy/sync-core-api.sh`（增量 rsync + 蓝绿软链 + 健康检查自动回切；异常用 `--full` 兜底 / `rollback-core-api.sh` 回滚）。
2. core-job：`./gradlew :core-job:bootJar` → 上传 fat jar（**不要 `-plain.jar`**）→ 装 systemd unit（参照 `app-core-api.service` 自建 `app-core-job.service`，复用 `common/.env.prod`）→ **三个 attest job 不配 cron**（决策 3）。
3. 健康检查：`curl http://localhost:3001/core/health`（`"status":"ok"`）、`curl http://localhost:3001/.well-known/jwks`（返回 kid=ifmixp1）。

#### Phase 4 — 冒烟（线上，curl 明文 dev 通道不可用时走客户端）

- 建装：`m_auth_install_createIosInstall` → installToken + attestationStatus=30
- 错误格式：带无效 token 调任一接口 → HTTP 401 + body 顶层 `{"code":"401000","msg":...}` + errors 数组
- JWKS、`/actuator/health` UP、日志无 `attest.config_invalid` / 启动 ERROR

#### Phase 5 — 客户端发布与灰度

1. 客户端包：persisted query manifest（40 条，含 `q_ai_scan_getMyById` 改名）、`env.ts` prod `wireKey={kid:1, pubHex}`、wire 默认开关 **false**、push/attest flag 默认关。
2. 发版后灰度：OTA 开 wire 开关（性能门槛 p95<10ms）→ `APP_ATTEST_GLOBAL_ENABLED=true` 重启（attestation 开始记录，OBSERVE 不拦截）→ 按 §4.5 指标再切 ENFORCE。
3. 回滚预案：app 层面 `rollback-core-api.sh`；wire/attest 均有 OTA 关闭开关；DB 首版无回滚需求（V1 终态 + 数据备份）。

## v1.0.6 增量（2026-10-06：RPC 试点、命名统一与模块结构调整）

本组变更全部为客户端 breaking，需与客户端发布节奏协同：

- **GraphQL operationName 与 reqName 统一四段式**：39 个顶层 field（`@DgsQuery/@DgsMutation`）全量改名（customer 作用域 CRUD 动词后带 `My`，create 例外；专名动词与 install/session 不加）；persisted query 文本内 operation name == manifest key == 顶层 field name。39 条全量名单即 `persisted-queries/customer/customer.json` 的 key。
- **media resource 改名 file**：`m_media_media_*` → `m_media_file_presignUpload` / `m_media_file_presignDownload`；表 `core_media_upload_record` → `core_media_filerecord`（V16，纯 RENAME，已在本地库验证）。
- **createInstall 按平台拆分**（attest 规格 v6）：`m_auth_install_create` → `m_auth_install_createIosInstall` / `m_auth_install_createAndroidInstall`（决策 10）；客户端 SDK 按 `platform` 选 action，manifest 现 40 条。
- **legacy 兼容整体删除**（决策 8）：fallback 开关链路 + legacy 限流计数器 + DateTime epoch millis。发布 env 不再需要 `APP_LEGACY_INSTALL_ID_FALLBACK`。
- **customer/install 并入 auth 模块**：服务端内部结构（`modules/auth/{install,customer}`、`entity/auth/`）+ reqName namespace 变化（`m_auth_install_*`、`m_auth_customer_*`）。
- **RPC URL 定稿 `POST /customer/core/greq/{reqName}`**：原 `/api/customer/core` 方案废弃；客户端 R0 已实现的 `/api/customer/core` URL 需在联调前同步调整。

### v1.0.6 增量（2026-10-07：请求解析职责收敛）

- **`WireCryptoFilter` 只解密**（body 四键原样透传 + 缓存 attribute，headers 不动）；**`RequestParser` 只暴露 `parseMeta`/`parseAuthorization`**（逐字段 parse 方法全删，meta 不含 accessToken）；`Actor` 加 `installId`/`tokenType`（install token 也产出 Actor），attribute 导出删除。meta 结构违规 400003 → 400000。见 wire 设计「meta 进 body」节。

### v1.0.6 增量（2026-10-07：demo E2E 走查修复）

- **V17**：`core_demo_todoitem` 补 `note` 列（实体/schema 早已有，建表迁移遗漏）。
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
- **新 env 两个**：`APP_ATTEST_GLOBAL_ENABLED`（默认 false，全局 kill switch）、`APP_ATTEST_CHALLENGE_SECRET`（`current[,previous]`，32 字节 base64；全局开关开时缺失 = 配置无效 fail-closed → 503002 + 节流日志 `attest.config_invalid`）。**【2026-10-07 修订】challenge secret 迁为 per-project 字段 `app_attest_config.challengeSecret`（优先），env 降级为回落**——决策记录 11。
- **DB 迁移 V14/V15**：`core_project_serverconfig.app_attest_config`（JSONB）；`core_install.store_type`（INT NULL）；新表 `core_install_attestation`（含 `attestation_object` 回填列）。先 `flywayMigrate` 再发新代码。
- **core-job 三个新任务**（`--job.name`）：`attestReceiptBackfill`（Apple receipt 回填，attestation_object 换 receipt）、`attestFraudMetricRefresh`（DeviceCheck two bits，失败指数退避封顶 24h；deviceCheck 配置缺失跳过不报错）、`attestEvidenceCleanup`（evidence 90 天清理）。外部 cron 触发，频率见规格 §5.7/§5.8（backfill/refresh 建议每日，evidence 每日）。**【2026-10-05 运维决定】执行入口已注释（`AttestJobs` 三个 @Bean），线上暂不调度、不配 cron**——不影响 createInstall/recover/attestExisting（纯本地验证，不依赖 receipt/fraud_metric）；代价 = fraud_metric 缺失（二期策略需要时再恢复）+ `attestation_object` 列随 install 增长。恢复：取消 `AttestJobs.kt` 内注释 → 部署 → 配 cron（首次启用会回填全部历史行，注意对 Apple 的集中请求量）。
- **发布顺序**：服务端（含 schema + allowlist）先上线（全局开关关、无 project 配置 → 现网零影响），客户端再发布（flag 默认关）；随后 `APP_ATTEST_GLOBAL_ENABLED=true` + project 写 `app_attest_config`（mode=OBSERVE 只配 ios + 独立 `challengeSecret`）→ 观察灰度指标（规格 §8）→ 满足 §4.5 后切 ENFORCE。
  - 上线前检查清单（规格 §9，不可跳过）：App ID 开 App Attest capability + 两套 profile；Archive `.app` codesign 确认 entitlement 值；TestFlight 真机冒烟（production verifier 跑通 create + recover）；平台发布清单（`app.config.js` 声明 platforms 含 android、EAS 有 android profile 时，1b 完成前**禁止切 ENFORCE**）。

## v1.0.6 增量（`feature/install`）

相对 v1.0.3 的主要变化，发布前逐项确认：

- **install 体系**：`m_auth_install_create` / `m_auth_install_updateOne`；token 增加 `type`（5=install / 10=customer）与 `iid` claim；`core_install` + `core_install_customer_relation`（V5/V6）。
- ~~**老 app 兼容**：`app.auth.legacy-install-id-fallback`~~ **2026-10-06 已删除**（决策 8）：v1.0.6 未发布，无兼容负担；installId 一律取 token 可信 iid。
- **扫描计数迁表**：`core_customer.scan_count / deep_research_count` → `core_ai_scanmetrics`（V9，旧列暂留，发布完成后另起迁移删除）。
- **AI key 池**：轮询 + Redis 分布式冷却；`core_ai_agnes_key` → `core_ai_apikey`（V8）。
- **错误透出**：线上（`app.expose-errors=false`）5xx 只返回通用文案。
- **日志**：文件日志改为 JSON（logstash 格式，一行一条）；MDC 上下文字段 `rid pid iid cid ip bot plat av ov loc cur cty` 为顶层字段；请求日志 `method/path/httpStatus/duration(ms 数值)/req/res`；请求头/响应头 `x-req-id`。线上看日志需 `jq`，日志采集侧按 JSON 解析。

发布注意：
- **DB 迁移顺序**：先 `flywayMigrate`（V4–V9）再发新代码；V6 改 `install_id` 列类型（text→uuid），旧代码在迁移后写入会失败，需短暂停机或先停旧实例。
- `AUTH_JWT_PRIVATE_KEY` 必须配置（缺失启动即失败）。

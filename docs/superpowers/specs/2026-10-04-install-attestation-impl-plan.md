# install attestation（App Attest 1a）实现计划 — 进度文档

> **本文件是执行进度与交接文档**。任何 agent 接手时：先读本文件 → 再读规格
> `docs/superpowers/specs/2026-10-04-install-attestation-design.md`（注意其 §0 v5 修订）→
> `AGENTS.md`。按下面任务的「状态」继续执行，**不要重做已完成的任务**；
> 每完成一个任务/子步骤，立即更新本文件的状态行与任务状态。
>
> - 分支：`feature/attest`（不要自行 commit，除非用户明确要求；若 commit 必须先跑 GitNexus detect-changes）
> - 最后更新：2026-10-05（**服务端全部完成并已 commit**：ifmix_server `feature/attest` 的 `c8f1df6`（T5-T7 + T11 文档 + [中] key_reused 修复）+ `6042e06`（本文档）；antique 前端 T8/T9/T10 在 `2f16c84`。V15 迁移已真库验证）
> - 当前阶段：**服务端收尾完成** —— 1a 机器可做部分全部闭环（代码/测试/文档/commit/flyway 真库）。仅剩人工项：真机 fixture（§10.1）+ TestFlight 冒烟（§9）+ §10.4 Apple 端点假设实测核实（集中在 core-job 两个 Impl）

---

## 0. 一页纸摘要

在 `ifmix_server`（服务端，Kotlin/Spring Boot 4/Jimmer/PostgreSQL/Redis）与
`/Users/jason/ai/myprojects/antique`（客户端，Expo/React Native monorepo）实现
install 平台证明（iOS App Attest）一期 1a：challenge（无状态 HMAC）+ createInstall 绑定
Secure Enclave key + recoverInstall（assertion 找回）+ attestExisting（存量补证）+
两层限流（IP/install）+ core-job（receipt 回填 / fraud metric 刷新 / evidence 清理）+
客户端状态机与协调器。Android（Play Integrity）是 1b，**本期不实现**，协议留在规格。

规格文档已经过 5 轮 review。第五轮 review 结论已由用户确认按建议修改文档并实现，
**全部决策记录在 §2（已定决策）**，规格 §0 的 v5 修订表也在同步写入。

---

## 1. 环境事实（已核实，勿重复探索）

### 服务端（ifmix_server）

| 事实 | 位置 |
|---|---|
| ErrorCode 枚举（externalCode, status） | `core-api/src/main/kotlin/com/ifmix/core/api/infra/http/ErrorCode.kt:6-25`。现有：INVALID_REQUEST 400000 / UNAUTHORIZED 401000 / FORBIDDEN 403000 / NOT_FOUND 404000 / RATE_LIMITED 429000 / IAP_VERIFY_FAILED 402000 / AI_UNAVAILABLE 503000 / INTERNAL 500000 / APP_CONFIG_MISSING 400002 / AUTH_PROVIDER_FAILED 401001 / TOKEN_EXPIRED 401002 / REFRESH_EXPIRED 401003 / QUOTA_EXCEEDED 429001。**403001/403002/404001/409001/429002/503002 均未占用** |
| 错误附加数据 | `ApiError.kt:4-8`（errorCode/message/details）；REST `GlobalExceptionHandler.kt:38-43`（仅 AI_UNAVAILABLE 有 Retry-After header）；GraphQL `GraphQLExceptionHandler.kt:52-59` extensions 只有 code/errorName。**GraphQL 无 Retry-After → 本次加 `extensions.retryAfterSec`** |
| RateLimiter | `core-api/.../infra/ratelimit/RateLimiter.kt`。`checkFixedWindow(subject, limit, windowSec): Boolean`（行 68，key=`ratelimit:fw:${subject}:${windowSec}`，INCR+EXPIRE）；tier 版 `check(ctx,subject)`/`refund` 只有 `RateLimiterTest` 用；降级 `log.warn` + 放行（行 33-35、71-75）；Redis 客户端 `StringRedisTemplate`（Lettuce）；**全仓库无 setIfAbsent（SET NX EX）先例** |
| checkFixedWindow 调用方 | `InstallFetcher.kt:28`（"install:$clientIp", 10, 60）、`AiFetcher.kt:241-242`（`$prefix:min:$ip` / `$prefix:day:$ip`；scan 5/min+500/天、DR 3/min+300/天，行 249-252）、`CustomerFetcher.kt:36`（裸 clientIp, 10, 60；createAnonymous；行 33 先 mustGetTokenInstallId 再限流） |
| InstallFetcher / createInstall | `bff/graphql/customer/install/InstallFetcher.kt`：m_install_createInstall（行 23-35，无鉴权）→ `globalTx.withTx(ctx) { installFacade.createInstall(txCtx, deviceInfo) }` → `InstallAggHandler.kt:27-50`（UuidV7 主键 + AuthJwtService.signInstall 签 installToken）。CreateInstallResult 现有 installId/installToken |
| ProjectServerConfig | 实体 `entity/project/ProjectServerConfig.kt:10-21`（`@Serialized @Column(name="fcm_config") val fcmConfig: Map<String,Any?>?` 是 JSONB 列先例）；Facade `modules/project/ProjectServerConfigFacade.kt:12-21`（仅 findFcmConfig，**无缓存**）；表 DDL `V14__project_server_config.sql`（project_id varchar(64) 唯一） |
| infra 调 Facade 先例 | `infra/push/FirebaseAppRegistry.kt:24-28` 注入 ProjectServerConfigFacade；缓存 = ConcurrentHashMap + Optional null 哨兵（行 31-38），重启生效 |
| core_install | 实体 `entity/install/Install.kt:11-54`，继承 BaseProjectEntity（UUID id + projectId: String + MutableProps）；platform Int?（10=ANDROID/20=IOS/30=WEB，取 x-client-platform）；DDL `V5__install_tracking.sql`（id uuid、project_id text）；**无 store_type** |
| tokenInstallId / legacy fallback | `infra/http/ActionContext.kt`：行 96 `mustGetTokenInstallId()` → 401000；行 99 `installIdOrNull() = tokenInstallId ?: legacyInstallId`；开关 `app.auth.legacy-install-id-fallback`（`application.yml:116`，env `APP_LEGACY_INSTALL_ID_FALLBACK`，默认 true）；RequestParser.kt:202-211 解析 x-install-id |
| trusted documents | 恒定开启：`infra/graphql/trusted/TrustedDocumentConfig.kt:22-26`（PreparsedDocumentProvider）；allowlist `core-api/src/main/resources/graphql/persisted-queries/customer/customer.json`；`TrustedDocumentProvider.kt:34-58` **按 reqName 取 allowlist 文本执行、完全忽略请求 body**；未命中 → 403000。reqName 由 `ReqNamePathInterceptor` 从 `/greq/{reqName}` 提取 |
| installToken | `infra/auth/AuthJwtService.kt`：行 35 `TOKEN_TYPE_INSTALL=5`；`signInstall` 行 70-84（iss/aud(projectId)/jti/iat/type=5/iid，**无 exp**）；verify 行 102-105 无 exp 视为不过期 |
| refresh 会 bind | `modules/auth/handler/AuthAggHandler.kt:252-300`，行 291-293：customer 的 refresh 幂等 bind(iid, customerId)；`InstallAggHandler.kt:109-124` bind 幂等（NoOp/Insert/Reactivate） |
| FOR UPDATE 先例 | Jimmer 0.11.5 无 forUpdate → 用 JdbcClient 裸 SQL：`modules/ai/repo/ScanRecordRepository.kt:241`（注释：经 DataSourceUtils 加入当前事务） |
| GlobalTxRunner | `infra/tx/GlobalTxRunner.kt:20-47`，`withTx(actionCtx, body)`；用法见 InstallFetcher.kt:33 |
| i18n | 错误码不做服务端本地化（客户端按 code 本地化）；MessageSource 只用于 push 文案（`i18n/messages*.properties`，键 `push.*`） |
| ScanQuotaConfig | `infra/ratelimit/ScanQuotaConfig.kt:19-25`，终身配额默认 Int.MAX_VALUE |
| core-api 依赖 | firebase-admin 9.7.0 ✓（GoogleCredentials 可用）；无 webauthn4j（需加 `com.webauthn4j:webauthn4j-appattest:0.30.1.RELEASE`，已 smoke test：Jackson 2.21.4 与 tools.jackson 3.1.4 共存 OK）；JDK toolchain 25；nimbus-jose-jwt 9.40 已在 core-api |
| core-job | 依赖仅 core-common/batch/jdbc/postgresql（**无 Jackson、无 nimbus**，需新增）；唯一 job=anonymousCleanup（`JobDispatcher.kt` 按 `--job.name` 分发，tasklet 模式，参照 `customer/AnonymousCleanupJob.kt`）；数据访问 `JdbcClient`（非 JdbcTemplate） |
| actuator | 无自定义 HealthIndicator；`management.endpoints.web.exposure.include: health` |
| Gradle 命令 | `./gradlew :core-api:compileKotlin` / `./gradlew :core-api:test` / `./gradlew :core-job:compileKotlin` / `./gradlew :core-job:test`；迁移手动执行 `./gradlew :core-api:flywayMigrate` |
| Flyway 迁移目录 | `core-api/src/main/resources/db/migration/`（最新版本号 **需实现时确认**：目前至少有 V14__project_server_config.sql，取 max+1） |

### 客户端（/Users/jason/ai/myprojects/antique，Expo RN monorepo）

| 事实 | 位置 |
|---|---|
| shared api | `apps/shared/src/api/client.ts`：ServerApiOptions 行 217-242；ensureInstall single-flight 行 304/363-387（inflightInstall，persistInBackground best-effort）；clearInstall 行 331-334；401 重建 raw.anonymous 行 391-410；`makeGqlOpts`/`makeInstallGqlOpts`/`makeAuthlessGqlOpts` 行 336-361；行 262 注释：不再发送 x-install-id |
| codes.ts | `apps/shared/src/api/codes.ts`（43 行，14 个码常量；503001=客户端合成 SERVICE_UNAVAILABLE）；`envelope.ts:37` isServiceUnavailable；`retry.ts:22-26` isTransient（503001/网络错误 → true；429/401/403 不重试）；retry.ts `retryTransient` 默认 3 次 delays [300,1000]ms、`persistInBackground` 1+2 次 |
| graphql.ts | `apps/shared/src/api/graphql.ts`：_API_ENTRIES 行 422-459（37 条）；PERSISTED_QUERIES 行 466；SENSITIVE_LOG_KEYS 行 504 = {accessToken, refreshToken, credential, deviceSecret}（**缺 installToken**）；非 JSON 响应 → 503001（行 566） |
| flags 模式 | `apps/antique/src/features/push/flags.ts`（46 行）：OTA 默认 false + AsyncStorage dev override + `export let` 可变绑定 |
| dev-settings | `apps/antique/src/app/dev-settings.tsx`（211 行）：setActiveEnv 行 66-69（ENV_ORDER local/dev/prod）；clearCredentials 按钮；入口=隐藏手势（support.tsx 标题连点 5 次，线上也可达） |
| 清凭证 | `features/install/clearCredentials.ts`（17 行，并行 clear installStore+tokenStore，需重启）；`features/account/wipeLocalUserData.ts:13-31`（保留规则 `DEVICE_KEEP=['antique.freeScanCount']` 精确匹配；清 AsyncStorage `antique.*` + SQLite + 图片目录；**SecureStore install 凭证设备级保留**） |
| 存储 | `lib/installStore.ts` / `lib/tokenStore.ts`（expo-secure-store）；固定 key `lib/storageKeys.ts`：PREFIX='ifmix.antique'，installCredentials.v1 / customerSession.v1（**不分 apiEnv**） |
| config | `apps/antique/src/lib/config.ts`（57 行）：EXPO_PUBLIC_PROJECT_ID、getActivePreset()（EXPO_PUBLIC_ENV）；**无 storeType** |
| 启动 | `apps/antique/app/_layout.tsx`（117 行）：第一个 effect 行 66-77 先 `await loadEnvOverride()`（行 68）再做其余本地初始化；**独立 effect 行 80** `useEffect(() => ensureInstallWhenOnline(() => api.installEnsure()), [])`；`features/install/ensureInstallWhenOnline.ts`（fire-and-forget + 网络恢复监听） |
| @expo/app-integrity | 未安装。安装命令（仓库约定）：`pnpm --filter @ifmix/antique exec expo install @expo/app-integrity@57.0.2`。已核实源码（57.0.2 / IntegrityModule.swift）：attestKeyAsync/generateAssertionAsync 内部 `clientDataHash=SHA256(utf8(challenge))` 返回 base64；无 config plugin；错误码 6 个 ERR_APP_INTEGRITY_{FEATURE_UNSUPPORTED,INVALID_INPUT,INVALID_KEY,SERVER_UNAVAILABLE,SYSTEM_FAILURE,UNKNOWN}；Android 另有 PROVIDER_INVALID |

---

## 2. 已定决策（第五轮 review，2026-10-04，实现必须遵循）

1. **receipt 回填（方案 b，异步）**：attestation 对象不含 receipt；createInstall 纯本地验证不碰 Apple。
   `core_install_attestation` 增加 `attestation_object BYTEA NULL`（原文入库），core-job 回填任务
   `POST https://api-appattest.apple.com/v1/attestations`（`{key: keyId, attestation}`，无需 DeviceCheck JWT——
   如核实需要再补）换取 receipt：成功 → 写 receipt、`next_refresh_at=now+24h`、清 attestation_object；
   Apple 侧 attestation 一次性消费，「已使用」4xx → 清 attestation_object + 放弃（打日志不重试）；
   网络/5xx/429 → refresh_failure_count+1 指数退避（封顶 24h）。
2. **fraud metric 刷新更正**：`attest_data`（DeviceCheck JWT，host 按 ios.env）响应是
   `bit0/bit1/creationTimestamp`，**不含新 receipt、无"下次允许刷新"字段**；fraud_metric=bit0/bit1（0..3），
   `next_refresh_at=now+24h`（服务端策略），失败指数退避；receipt 一期不轮换（receipt_expires_at 不写）。
3. **Retry-After 契约**：GraphQL error `extensions.retryAfterSec`（整数秒）。429000=短窗口剩余秒数（必带）、
   429002=到 UTC 零点秒数（必带）、503002=可选。客户端 codes.ts/retry.ts/调度器读取。REST 不动。
4. **ENFORCE 配置校验口径**：API 路径必填仅 ios=`teamId/bundleId/env`、android=`packageName/certSha256Digests`；
   `deviceCheckKeyId/deviceCheckPrivateKey/serviceAccount` 可选，缺失不影响三个接口的有效性，
   只让 core-job 跳过任务并打日志。配置无效（fail-closed）语义不变（§4.1）。
5. **429002 范围**：createInstall 的 attested/unverified IP 日窗口 + attestExisting 的新 key 日额度（去掉"createInstall 专用"）。
6. **createAttestChallenge 限流**：10/60s → **100/60s/IP**（纯 HMAC 计算），与 createInstall 入口对齐，避免 CGNAT 瓶颈；recoverInstall 维持 10/60s。
7. **findAttestConfig 缓存**：参照 FirebaseAppRegistry（ConcurrentHashMap + null 哨兵，重启生效）。
8. **客户端状态机补丁**：backfill 不与 bootstrap 并发（等首次 bootstrap cycle 结束再调度，共用 single-flight/定时器/Retry-After）；
   REGISTERED 且 installId≠targetInstallId → 视同 EMPTY 生成新 key（REGISTERED 被覆盖）；
   no-proof 收 403001 → 新 outcome `unsupported`（状态不变，UI 分类 DEVICE_UNSUPPORTED）；
   EMPTY 先 fetchChallenge（enabled=false → no-proof 不生成 key）再 generateKeyAsync。
9. **recover 与 mode/全局开关无关**：只要 ios 配置存在（未配置→400000）且 challenge secret 可用（缺失→503002）。
10. **Android 重放敞口（记录进规格，1b 实现时生效）**：Redis 故障期去重失效，一份 integrityToken 5 分钟内可重放多 install（受 attested 日窗口封顶）；ENFORCE 期间 Redis 故障按 P0 告警。
11. **杂项**：限流 key 统一格式补 `install:ip:day:{attested|unverified}:` 变体；core-job 数据访问写 JdbcClient；`install.recover` 日志补 mode/provider 字段；webauthn4j production 标志=AAGUID 检查（`appattest\0…0` vs `appattestdevelop`），dev/prod 共用同一 Apple 根证书（只需固定一份 PEM + 指纹）。

---

## 3. 任务分解与进度

状态图例：`todo` / `in_progress` / `done` / `blocked`（写明原因）/ `skipped`（写明原因）。
每个任务完成后在「备注」记录关键产物路径与任何偏离规格的决定。

### T0 规格修订（把 §2 决策写进 design.md 的 §0 v5 及各章节）

- [ ] T0.1 头部状态行 v4→v5 + §0 新增 v5 修订表
- [ ] T0.2 §3.1 补 receipt 回填说明；§3.2 补 Android 重放敞口；§3.3 补 global-off recover
- [ ] T0.3 §4.1 配置校验口径；§4.4 429000/429002 retryAfterSec + 去"专用" + 传输说明
- [ ] T0.4 §4.6 challenge 100/60s + yaml + key 格式变体 + Retry-After 引用
- [ ] T0.5 §5.1 enabled 平台判定说明 + challenge 描述；§5.2 缓存/VerifiedProof/production 注
- [ ] T0.6 §5.4 attestation_object 列 + 回填索引；§5.5 recover 日志字段；§5.6 JdbcClient + 回填端点
- [ ] T0.7 §5.7 receipt 保留行；§5.8 重写（回填 + 刷新两个任务）
- [ ] T0.8 §6.2 unsupported outcome；§6.4 存储key/EMPTY顺序/REGISTERED补证/结果表；§6.8 backfill 互斥 + retryAfterSec 读取
- [ ] T0.9 §7 测试补充（retryAfterSec、配置口径、core-job 回填、Android 重放、global-off recover）
- [ ] T0.10 §10.4 端点核实状态更新；§11 文档清单补充

**状态：done**（备注：24 处编辑全部落盘：§0 v5 修订表、§3.1 receipt 回填、§3.2 Android 重放敞口、§3.3 global-off recover、§4.1 配置口径、§4.4 retryAfterSec+429002 归属、§4.6 challenge 100/60s+key 变体、§5.1 enabled header 判定、§5.2 缓存/production 注/VerifiedProof、§5.4 attestation_object+回填索引、§5.5 日志字段、§5.6 JdbcClient+回填端点、§5.7 receipt 保留行、§5.8 重写为回填+刷新两任务、§6.2 unsupported、§6.4 存储 key/EMPTY 顺序/REGISTERED 补证/结果表、§6.8 backfill 互斥+Retry-After 读取、§7 测试 4 处、§10.4 端点模型、§11 retryAfterSec）

### T1 GitNexus 影响分析（AGENTS.md 强制，编辑现有符号前）

对以下符号跑 `node .gitnexus/run.cjs impact "<symbol>" --direction upstream --repo .` 并把结果记录到 §5：
`checkFixedWindow`、`createInstall`（InstallFetcher/InstallAggHandler/InstallFacade）、`ErrorCode`、
`mustGetTokenInstallId`、`findFcmConfig`（作为 findAttestConfig 的模式参照）。
**状态：todo**（备注：）

### T2 WP-A 服务端基础（可与 T3 并行）

涉及：`core-api/src/main/resources/db/migration/V{N}__install_attestation.sql`（N=现最大+1）、
`entity/install/InstallAttestation.kt`（Jimmer interface entity）、`Install.kt` +storeType、
`ProjectServerConfig.kt` +appAttestConfig、`modules/install/repo/InstallAttestationRepository.kt`、
`infra/http/ErrorCode.kt` +6 码。

要点：
- 表结构与索引**完全按规格 §5.4（含 v5 修订：attestation_object 列 + 回填索引）**；
  project_id TEXT NOT NULL；sign_count BIGINT NOT NULL DEFAULT 0；status INT（10 ACTIVE/20 BLOCKED/30 RETIRED）；
  唯一索引 `(project_id, provider, subject) WHERE subject IS NOT NULL`。
- Repository 方法：insert、findBySubject(projectId, provider, subject)、findByInstall(projectId, installId)、
  条件更新 counter（`UPDATE ... SET sign_count=:new, last_used_at=now WHERE id=:id AND status=10 AND sign_count<:new` 返回 Boolean）、
  countActiveByInstall、retireOldestActive（ORDER BY created_at ASC, id ASC LIMIT 1 → status=30）、
  lockInstallRow（JdbcClient `SELECT id FROM core_install WHERE project_id=:p AND id=:id FOR UPDATE`，参照 ScanRecordRepository.kt:241 的 DataSourceUtils 模式）。
- ErrorCode 新增：ATTESTATION_FAILED("403001")、ATTEST_KEY_BLOCKED("403002")、
  ATTEST_KEY_BOUND_TO_OTHER_INSTALL("409001")、INSTALL_NOT_FOUND("404001")、
  ATTESTATION_UNAVAILABLE("503002")、INSTALL_DAILY_LIMITED("429002")——status 对应 403/404/409/429/503，
  参照现有枚举项的 status 选择。

**状态：done**（备注：V15__install_attestation.sql 与规格 §5.4 逐条 diff 一致（29/29，含 attestation_object + backfill 索引）；实体 InstallAttestation.kt（BaseProjectEntity + @Serialized JSONB，附 AttestationStatuses 常量 10/20/30）；InstallAttestationRepository（insert/findBySubject/listByInstall/countActiveByInstall/retireOldestActive/updateSignCount 条件更新/lockInstallRow 用 JdbcClient FOR UPDATE，DataSourceUtils 模式照 ScanRecordRepository）；Install +storeType；ProjectServerConfig +appAttestConfig；ErrorCode +6（409001 用 CONFLICT，编译通过）。:core-api:compileKotlin 绿。注意：WP-A 报告提到 build.gradle.kts 由 WP-C 修改）

### T3 WP-B 限流重构（可与 T2 并行）

涉及：`infra/ratelimit/RateLimiter.kt`、新建 `infra/ratelimit/RateLimitProperties.kt`（或按仓库习惯命名）、
`application.yml`、三个 Fetcher 的调用点。

要点（规格 §4.6 + §2 决策 6）：
- 新接口：`sealed interface RateLimitResult { Allowed; Limited; Degraded }`；
  `enum class Window { MINUTE, UTC_DAY }`；`fun check(window: Window, key: String, limit: Int): RateLimitResult`
  （UTC_DAY 内部拼 `:{yyyy-MM-dd}`，**UTC 时区**）。保留 tier 版 check/refund 不动（只有测试用）。
- 删除 `checkFixedWindow`，三个调用方全部迁移：
  - createInstall 入口 100/60s/IP（key `ratelimit:{pid}:install:ip:min:{ip}`）
  - createAnonymous legacy 10/60s（无可信 iid 时）
  - scan legacy 5/min+500/天、DR legacy 3/min+300/天
  - 降级（INCR null/异常）→ Degraded 放行 + ERROR `event=ratelimit.degraded`（按 subject 前缀节流：首次 1 条、之后每分钟最多 1 条；恢复后 INFO `ratelimit.recovered`）；日志不带 IP 原文
- 配置 `app.ratelimit.*`（@ConfigurationProperties，启动绑定，重启生效）默认值按规格 §4.6 yaml
  （challenge ip-minute=**100**（决策 6）、recover 10、attest-existing 10+3、anonymous/scan/deep-research 全套）。
- 下游 install 层限流的**执行框架**（install 层→IP 层→legacy 三策略、`tokenInstallId` 只认 token）
  在 T5（WP-D）接线时实现；本任务只做 RateLimiter 本体 + 配置 + 现有调用点迁移。

**状态：done**（备注：RateLimiter 重构完成——`RateLimitResult`（Allowed/Limited(retryAfterSec)/Degraded）+ `Window{MINUTE,UTC_DAY}`（UTC 日期拼接）+ `check(window,key,limit)`；tier 版 check/refund 原样保留（内部改注入 Clock，默认 systemUTC）；checkFixedWindow 已删除；降级节流：同前缀每分钟 1 条 ERROR `ratelimit.degraded`、恢复 INFO `ratelimit.recovered`、subject 前缀=key 去掉最后一段（不含 IP/iid）。新建 RateLimitProperties（app.ratelimit 全套默认值，challenge=100；注册沿用 RateLimitBeans @EnableConfigurationProperties；tier 的 RateLimitConfig 共用前缀但字段不重叠）+ application.yml 全量块。ApiError +retryAfterSec（第 4 可选参），GraphQLExceptionHandler extensions 追加。三处调用方迁移完成（install/anonymous legacy/scan+deep-research legacy，key 统一 `ratelimit:{pid}:{action}:...`，AiFetcher 原 `deepresearch` 前缀改名 `deep-research`）。测试 12/12 绿（4 tier 存量 + 8 新）；全量 :core-api:test 回归 300 个中 7 个类失败全部为**环境性失败**（本地 PG 缺 ifmix_core_local 库，E2eTestBase 连接失败，与改动无关））

### T4 WP-C infra/attest 组件（T2 的实体完成后可并行做，纯新增文件）

新建包 `core-api/.../infra/attest/`（规格 §5.2、§3.1、§4.1）：
- `AttestConfig.kt`：DTO + JSONB 解析 + 校验（口径按 §2 决策 4；mode OFF/OBSERVE/ENFORCE）。
- `AttestChallengeCodec.kt`：HMAC challenge（payload=ver(1)=1‖issuedAtSec(8,BE)‖random(16)，
  mac=HMAC-SHA256(secret, "ifmix-attest-ch-v1\n"+projectId+"\n"+payload)，base64url no padding → 76 字符）；
  secret 支持 `current,previous`；校验窗口 `[-30s, +300s]`；注入 `Clock` 便于测试。
- `AttestReplayGuard.kt`：`markUsed(key, ttl): FIRST/REPLAY/DEGRADED`（StringRedisTemplate setIfAbsent）；
  DEGRADED 打节流 ERROR `attest.redis_degraded`（与 ratelimit 的节流控制器同模式）。
- `AppAttestTrustAnchors.kt`：classpath `attest/apple-app-attestation-root-ca.pem` + 启动校验 SHA-256 指纹常量；
  构造 `KeyStoreTrustAnchorRepository` + `DefaultCertPathTrustworthinessVerifier`。
  **PEM 获取**：从 Apple 官方下载（https://www.apple.com/certificate-authority/private/ 页面的 App Attestation Root CA，
  或 `cacerts.apple.com`）；下载后计算指纹写进常量，代码注释注明来源 URL 与日期；下载失败则 blocked 并在本文件记录。
- `AppAttestVerifier.kt`：webauthn4j `DeviceCheckAttestationManager`/`DeviceCheckAssertionManager`，
  production/development 两实例（`DCAttestationDataVerifier.production` true/false，按 ios.env 选用；
  该标志=AAGUID 检查，已核实源码）；进程内信号量（初值 32 可配）限制并发，拿不到 → UNAVAILABLE。
- `core-api/build.gradle.kts`：`implementation("com.webauthn4j:webauthn4j-appattest:0.30.1.RELEASE")`。
- 单测：AttestChallengeCodec（固定 Clock：签发/校验/篡改/跨 project/过期/previous secret）、
  AttestReplayGuard（FIRST/REPLAY/DEGRADED+日志）、AttestConfig（口径校验）、字节契约向量。
  AppAttestVerifier 的真机 fixture 测试（§10.1）**本期不做**（需真机），只留 TODO 注释与 `@Tag` 空壳可省。

**状态：done**（备注：infra/attest 全套完成：AttestConfig（fail-closed 收集 problems、deviceCheck* 可选口径）、AttestChallengeCodec（固定 Clock、current/previous secret、[-30s,+300s]）、AttestReplayGuard（FIRST/REPLAY/DEGRADED + attest.redis_degraded 节流）、AppAttestTrustAnchors（**PEM 已下载**：来源 `https://www.apple.com/certificateauthority/Apple_App_Attestation_Root_CA.pem`——注意正确 URL 是 certificateauthority 无连字符，规格旧链接 404；SHA-256(DER)=1cb9823ba28ba6ad2d33a006941de2ae4f513ef1d4e831b9f7e0fa7b6242c932 已固定，CN=Apple App Attestation Root CA）、AppAttestVerifier（DeviceCheckAttestationManager×2 + DeviceCheckAssertionManager，production 标志=AAGUID 检查，Semaphore 32，公钥为 65B uncompressed point，keyId=SHA-256）；build.gradle + webauthn4j-appattest 0.30.1.RELEASE。测试 43/43 绿（含 AttestByteContractTest 冻结的向量：challengeStr `AQAAAABlU_EA…CYYR`、clientDataHash `f64bce63…`、nonce `04b815a3…`——客户端需对齐）。0.30.1 的 API 形态：信任锚经 DefaultCertPathTrustworthinessVerifier(KeyStoreTrustAnchorRepository) 传入 manager；assertion 是 verify(DCAssertionRequest, DCAssertionParameters(DCServerProperty, DCAppleDevice))）

### T5 WP-D 接线（依赖 T2/T3/T4）

涉及：`infra/attest/AttestGuard.kt`（新）、`ProjectServerConfigFacade`（+findAttestConfig+缓存，决策 7）、
`InstallFacade`/`InstallAggHandler`（createInstall 带 proof/storeType、recoverInstall、attestExisting）、
`InstallFetcher`（createInstall 新流程 + 3 个新 mutation）、`schema/customer/install.graphqls`、
`graphql/persisted-queries/customer/customer.json`、`CustomerFetcher`/`AiFetcher`（下游 install 层限流）。

要点（规格 §4.3/§4.6/§5.1/§5.3/§6.7 + §2 决策）：
- AttestGuard 三方法：`verifyProof`（challenge 校验→WebAuthn4J→返回 VALID/INVALID/UNAVAILABLE，不套 mode）、
  `decideCreateInstall(verification, mode)`（§4.3 矩阵）、`consume`（ReplayGuard.markUsed）。
- createInstall 流程顺序（严格按 §4.6 伪代码）：分钟窗口 → proof/proofStatus 组合校验（§5.1 表）→
  verifyProof → decide → 日窗口（attested/unverified 分桶，INVALID/OBSERVE 放行进 unverified）→
  consume → `globalTx.withTx { installFacade.createInstall(...) }`；503002/403001/400000 不消耗日额度；
  事务阶段失败不退款。错误带 extensions.retryAfterSec（429000=分钟窗口剩余、429002=到 UTC 零点）。
- createInstall 绑定（§5.3）：iOS VALID 按 (projectId,110,keyId) 查，已存在 → 403001(key_reused) 不建 install；
  Android VALID 写 subject=null 行；platform 由 provider 派生；storeType 校验（{10,20} 外→400000，缺省 NULL，
  write-once；VALID 交叉核对不一致只打 `store_mismatch` 日志）。
- recoverInstall（§3.3）：限流 10/60s/IP → challenge 校验 → 只读查绑定（无→404000；BLOCKED/RETIRED→403002）→
  WebAuthn4J 验 assertion（counter>sign_count）→ consume → 事务内条件更新 counter（0 行→403001）→
  重签 installToken，返回 attestationStatus=10。与 mode/全局开关无关（决策 9）。
- attestExisting（§6.7）：鉴权只认 `action.tokenType==5 && actorId==null && tokenInstallId!=null`（否则 401000）→
  IP 10/60s → 服务端 OFF→30 → verifyProof（INVALID→20，UNAVAILABLE→503002）→ 预查绑定
  （同 install ACTIVE→幂等 10 跳过额度与消费；BLOCKED/RETIRED→403002；他 install→409001）→
  新 key 才查 3/install/UTC 日（超限 429002）→ consume（replay→403001）→ 事务内：
  FOR UPDATE 锁 install 行（404001）→ 锁内重查绑定（同上口径）→ 满 5 把 ACTIVE 时 retireOldest →
  插入 → 10；唯一冲突→回滚重查→映射 10/409001。
- createAttestChallenge（§5.1）：返回 enabled/challenge/expiresInSec=270；enabled 判定=全局开关&&配置可解析&&mode!=OFF
  &&（平台子对象存在，平台取自报 x-client-platform header，决策见 §5.1 v5 注）；纯计算不碰 Redis；限流 100/60s/IP。
- 下游 install 层限流（§4.6「下游接口的 install 层」）：createAnonymous/scan/DR 按「有 tokenInstallId→
  install 层→IP 层；无→legacy 严格阈值独立计数器；fallback 关闭后无 iid→401000」；key 全部带 projectId。
- GraphQL schema：CreateInstallInput +proof/proofStatus/storeType；CreateInstallResult +attestationStatus；
  新增 AttestChallengeResult、RecoverInstallInput、AttestExistingInput/AttestExistingResult、
  m_install_createAttestChallenge / m_install_recoverInstall / m_install_attestExisting（描述含限流说明）；
  **customer.json 同步更新**（TrustedDocumentProvider 按 reqName 取文本执行，必须与 schema 一致，
  否则运行时 403000/解析失败）。
- 结构化日志（§5.5）：install.attest / install.recover（recover 补 mode/provider 字段）。
- 单测：AttestGuard 判定矩阵（§7 列表）、attestExisting 全部分支（§7「补充」块）、
  createInstall 日窗口 12 项、storeType、attestationStatus 映射。

**状态：todo**（备注：）

### T6 WP-E core-job（依赖 T2 的表结构；与 T5 并行）

涉及：`core-job/build.gradle.kts`（+`com.nimbusds:nimbus-jose-jwt:9.40`、+Spring Boot Jackson starter）、
新包 `com.ifmix.core.job.attest/`。

要点（§5.8 + §2 决策 1/2）：
- `ReceiptBackfillJob`：JdbcClient 选取 `provider=110 AND status=10 AND receipt IS NULL AND attestation_object IS NOT NULL`；
  DeviceCheck 不需要 JWT 的 `/v1/attestations` 调用（JDK HttpClient）；成功写 receipt+next_refresh_at=+24h+清 attestation_object；
  「已使用」4xx → 清 attestation_object+日志 reason=already_used 不重试；网络/5xx/429 → failure_count+1 指数退避封顶 24h。
- `FraudMetricRefreshJob`：`receipt IS NOT NULL AND next_refresh_at<=now()`；nimbus ES256 JWT
  （kid=deviceCheckKeyId、iss=teamId、.p8 私钥；host 按 ios.env：production=api.devicecheck.apple.com /
  development=api.development.devicecheck.apple.com）；成功 fraud_metric=bit0*2+bit1、next_refresh_at=+24h、
  failure_count=0；失败退避；**deviceCheck 配置缺失 → 跳过 + 日志（不报错）**（决策 4）。
- `EvidenceCleanupJob`：`UPDATE core_install_attestation SET evidence=NULL WHERE evidence IS NOT NULL AND created_at < now()-interval '90 days'`。
- job 注册进 JobDispatcher（--job.name 分发，tasklet，参照 AnonymousCleanupJob）；
  `app_attest_config` JSONB 用 Jackson 解析（core-job 内独立最小 DTO，不放 core-common）。
- 单测：回填成功/已使用/退避；刷新成功/429 退避；evidence 清理。

**状态：todo**（备注：）

### T7 服务端编译 + 测试

`./gradlew :core-api:compileKotlin :core-job:compileKotlin` 全绿 → `./gradlew :core-api:test :core-job:test` 修复到绿。
（真机 fixture / Play Integrity 不在本期。）
**状态：done**（备注：`./gradlew :core-api:compileKotlin :core-job:compileKotlin` 全绿。全量 `:core-api:test` 398 个中 24 个失败全部为**环境性**（本地 PG 缺 `ifmix_core_local` 库，`PSQLException: FATAL: database` 静态初始化连接失败）：e2e 5 类（TodoMetaJsonb/SecurityE2eTest×2/Collection/FeedbackReasonsArray/WechatAuth）+ ClusterRegistryTest（同样 PG 连接），与改动无关——符合 T7 备注预设。T5 新增 5 个测试类全绿（AttestGuardTest 28 / InstallFetcherAttestTest 34 / CustomerFetcherRateLimitTest 9 / AiFetcherRateLimitTest 12 / InstallAggHandlerAttestTest 15，共 98）；T6 core-job 15 新 + 7 存量全绿（H2 PG 模式真 SQL + Fake HTTP）。非 e2e 单测无回归。flywayMigrate 因本地缺库未跑，V15 SQL 语法已由 T2 逐条对照 §5.4 核过）

### T8 WP-F1 客户端 shared api 层（antique 仓库；可与 T5/T6 并行，依赖规格即可）

涉及 `apps/shared/src/api/`：codes.ts、retry.ts、envelope.ts、graphql.ts、client.ts。
要点（§6.2/§6.6/§6.8 + §2 决策 3/8）：
- codes.ts：+403001/403002/404001/409001/429002/503002 注释与常量；isTransient +503002。
- retry.ts/envelope.ts：解析 error extensions `retryAfterSec`（如 ApiError 加字段 retryAfterSec?: number）。
- graphql.ts：CreateInstall selection set +attestationStatus；新 operation
  m_install_createAttestChallenge / m_install_recoverInstall / m_install_attestExisting；
  `_API_ENTRIES` + `PERSISTED_QUERIES` 同步；SENSITIVE_LOG_KEYS +installToken/assertion/attestationObject/
  integrityToken/challenge/nonce，proof 子树按不透明处理（只留 provider、字段长度、keyId SHA-256 前 8 位）。
- client.ts：install 协调器（mutex 短临界区 + installEpoch + clearInstallIfCurrent(expectedInstallId, expectedEpoch)；
  mutex 不跨外部 IO，参照 §6.8 伪代码）；ensureInstall 接 InstallProofProvider（ensureInstall 在锁外调 next/请求/report，
  next 传 {operation:'bootstrap'|'backfill', install:InstallSnapshot, fetchChallenge}）；403001/404000/404001/409001
  不进 retryTransient，403001 replay 最多原 proof 重发 1 次后转 recover；404001 收敛链
  （clearInstallIfCurrent → 建 B → 有 session 用 B 的 token refresh → 对 B 重新补证）；
  attestExisting 后台 workflow（makeInstallGqlOpts，每次启动最多一个，等首次 bootstrap cycle 结束）。
  注意 client.ts 很大，改动保持外科手术式。
- codegen：按仓库现有流程重新生成（先看 package.json scripts）。
**状态：done**（备注：apps/shared/src/api 全部完成并验证：新建 installProof.ts（ProofStep/ProofOutcome 含 unsupported/InstallProofProvider/InstallSnapshot/ChallengeResult + 手写 GraphQL 输入类型 + 零依赖 SHA-256 + keyIdFingerprint）、installCoordinator.ts（短临界区 mutex + installEpoch + clearInstallIfCurrent CAS + pauseAndDrain/resume）；修改 codes.ts（+6 码）、envelope.ts（ApiError.retryAfterSec）、retry.ts（isTransient+503002）、graphql.ts（CreateInstall +attestationStatus、3 个新 operation、_API_ENTRIES/PERSISTED_QUERIES、retryAfterSec 透传、SENSITIVE_LOG_KEYS+proof 子树脱敏）、client.ts（+302 行：ensureInstall 编排、403001/404000/404001/409001 不重试、≤2 步推进、installAttestExisting()、ServerApiOptions+installProof/installStoreType）。验证：typecheck 0 错误；13 套件 127 测试全绿（93 存量+34 新增）。遗留：服务端 schema 合入后跑 pnpm gen:gql 与 persisted-queries 同步（该脚本写 ifmix_server 侧，留给 T11）

### T9 WP-F2 客户端 app 层 + 状态机（依赖 T8）

涉及 `apps/antique/src/`：lib/attest.ts（新）、lib/config.ts（+storeType：iOS 固定 10）、
features/attest/flags.ts（新，照 push/flags.ts）、app/dev-settings.tsx（+开关）、
app/_layout.tsx（loadEnvOverride → initAttestation(ctx) → ensureInstallWhenOnline 串行，合并两个 effect）、
features/install/ensureInstallWhenOnline.ts（§6.8 统一调度：3 次快速重试→长退避 30s/2m/10m+网络监听，
Retry-After 优先，同一时刻一个定时器，后台不启动，UNSUPPORTED 与 30 不重试）、
features/account/wipeLocalUserData.ts（保留规则改「精确+前缀」：DEVICE_KEEP_PREFIXES=['antique.attestState.']，
fixture key 不保留）、features/install/clearCredentials.ts（改走协调器 resetAll）。
- lib/attest.ts：initAttestation(ctx)（幂等、ctx 变更抛错）、attestState 状态机（§6.4 全表：规则 1/规则 2、
  attemptId/超时、ATTESTED_PENDING 重发与过期转 RECOVER_PENDING、SERVER_UNAVAILABLE 同 key 重试、
  SYSTEM_FAILURE/UNKNOWN 重试 1 次后弃 key、UNSUPPORTED 终态带 appVersion、损坏状态保守处理 §6.8）、
  状态存 AsyncStorage `antique.attestState.{projectId}.{apiEnv}`（信封 schemaVersion/projectId/apiEnv/
  appAttestEnvironment/appVersion/state）、InstallProofProvider 实现（create/recover/attest_existing/no-proof/
  unavailable + outcome report）。
- @expo/app-integrity@57.0.2 安装（命令见 §1）；app.config.js entitlement 按 APP_ENV（development/production）+
  runtimeVersion '2'→'3'；真机 fixture 采样工具（§10.1）**本期不做**（需真机），留 TODO。
- 测试：状态机转移表逐条（§7 客户端块）、mutex 不跨 IO（fake mutex 抛错）、404 收敛集成、
  脱敏单测、persisted query 契约测试。
**状态：done**（备注：核实确认 T9 全部产物已落盘并 commit（antique 仓库 `feature/attest` 的 `2f16c84`，2026-10-05 00:36）：lib/attest.ts（46KB，状态机 §6.4 全表 + initAttestation + InstallProofProvider + @expo/app-integrity 唯一 import 点封装）、features/attest/flags.ts + AttestFeatureFlagControls、app.config.js entitlement `com.apple.developer.devicecheck.appattest-environment` 按 APP_ENV 映射 + runtimeVersion 2→3、_layout.tsx loadEnvOverride→initAttestation→ensureInstallWhenOnline 串行、ensureInstallWhenOnline.ts 统一调度（30s/2m/10m 退避 + Retry-After 优先 + single-flight + 后台不启动 + UNSUPPORTED/30 不重试）、wipeLocalUserData 精确+前缀保留规则、clearCredentials 走协调器 resetAll、config.ts storeType=10(iOS)、_layout 接 backfill workflow（api.installAttestExisting）。测试已 commit（attest.test.ts / attest.debug6.test.ts / ensureInstallWhenOnline.test.ts），运行结果见 T10。上一版任务书所称「被取消未产出」不成立——重派 agent 发现无需重写，避免重复劳动。遗留仍归 T11：gen:gql / persisted-queries 同步）

### T10 客户端验证

typecheck（antique 仓库的 tsc/lint 命令，进仓库后确认）+ 单测。
**状态：done**（备注：`pnpm --filter @ifmix/antique run typecheck`（tsc --noEmit）0 错误；全量 `npx jest` 30 套件 / 205 测试全绿。过程中修了 2 个测试自身的问题（非实现 bug）：① ensureInstallWhenOnline.test.ts 的 `flush` 用裸 `setTimeout(0)` 在该套件 fake timers 下永不触发（11 例 5s 超时）→ 改 `jest.advanceTimersByTimeAsync(0)`；② 该套件 makeDeps 默认 `backfill.shouldRun=false` 与「bootstrap 成功后接续补证」用例自相矛盾（§6.8 默认补证可跑）→ 默认改 true，「不跑」用例本就显式覆盖。api.test.ts 新增 `jest.mock('@/lib/attest')` + react-native mock（api.ts 经 attest.ts 引入原生依赖链，原套件未 mock 导致解析失败）。shared 层 127 测试绿（T8 已验）

#### T9/T10 代码 review 发现（2026-10-05，协调会话复核；待修，交给下一个 agent）

typecheck 0 错误；shared 127 + antique 205 测试全绿；§6.4 状态机、协调器锁规则、404001 收敛链、
persisted query、脱敏、wipe 保留规则、entitlement/runtimeVersion 均核对通过。以下为发现的问题：

- **[中1] 每个全新验证 install 会在下次启动补证时绑定第二把 key**。
  链路：`report('create_verified')` 拿不到 installId（契约只传 outcome）→ REGISTERED.installId 缺省
  （attest.ts:925-934）→ `shouldBackfillAttestation` 对「REGISTERED 且 installId 未知」返回 true
  （attest.ts:1016）→ 首个 bootstrap 成功后调度必然触发 backfill（_layout.tsx）→
  `next(backfill)` 对 REGISTERED+installId 未知走 runCreateFlow 生成**新 key**（attest.ts:846-851）
  → 绑定为第二条 attestation。后果：flag 开后每个 iOS 新装 2 把 SE key / 2 次 attest /
  fraud metric ×2 / 占 3/day 新 key 额度；attest.ts:926 注释「幂等 attestExisting 会补上」不成立
  （幂等要求同一把 key）。修复建议 (a)（推荐）：扩展契约 `report(outcome, meta?: { installId? })`，
  client 在 create/recover 成功时传 `result.installId`，attest.ts 写入 REGISTERED（符合 §6.4
  REGISTERED{keyId, installId}）；(b) `shouldBackfillAttestation` 对 REGISTERED 一律返回 false
  （实践安全：bootstrap 会先消费 REGISTERED→recover，但偏离 §6.7 触发表）。
- **[中2] 429002 + Retry-After 截断 10min → 循环烧 key**。ensureInstallWhenOnline.ts:24-25,101-107
  把 retryAfterSec 截到 10min；429002 的 retryAfterSec=到 UTC 零点（可能数小时）→ 每 10min 一个
  cycle：EMPTY→新 key→attest→429002→弃 key→循环，日限设备一天可烧几十把 key（Apple fraud
  metric/配额）。§6.4 要求「等到 Retry-After 再开始」。建议：429002 视为本次启动终结（goIdle，
  同 backfill 的 20/30），或对 429002 不截断 + provider 记 notBefore 防 EMPTY 立刻重新生成 key。
- **[中3] UNSUPPORTED 设备在 ENFORCE 下无限定时重试**（仅 ENFORCE 生效，当前 OBSERVE 无影响）。
  outcome 'unsupported' 后 client 抛 403001（非 transient）→ 调度器通用退避永远重试
  （30s→2m→10m 循环）；§6.8 明确 UNSUPPORTED 不做定时重试。建议：runCycle catch 里查
  `isAttestUnsupported()`（attest.ts 已导出）→ goIdle，或把 unsupported 转成调度器可识别的终结错误。
- **[低]** ① `apps/antique/src/lib/attest.debug6.test.ts` 是无断言的调试残留（console.log 噪音），
  删除（rule1 持久化失败场景并入 attest.test.ts，若未覆盖）；② client.ts `MAX_INSTALL_STEPS=2`
  与 §6.2「最多 2 步」示例（create→recover→create 共 3 请求）不符：实际最多 2 请求，recover 404
  后的「立即用新 key 重新 create」推迟到下一调度轮——收敛性不受影响，改代码为 3 或修规格文字，
  二选一；③ `runAttestExisting` 把 provider 的 'unavailable' step（如 challenge 网络瞬断）也当
  status=30 返回 → 本次启动放弃补证；应区分：unavailable → 抛出交调度退避，仅 no-proof → 30；
  ④ 未提交的 docs/CHANGELOG.md 写「createInstall 10/60s/IP」——规格 v4/v5 是入口 100/60s/IP
  （day：未验证 100 / VALID 1000），改掉；⑤ `clearAllAttestStates`（Dev）后，在途原生流程的迟到
  commitBestEffort 仍可能复活已清状态（mutex 只挡临界区、挡不住锁外流程的后续写入）——Dev-only，
  可接受，彻底修需 epoch 校验。

#### T5/T6/T7/T11（服务端）代码 review 发现（2026-10-05，协调会话复核）

验证：:core-api:compileKotlin + :core-job:compileKotlin 绿；attest 相关 156 测试全绿（0 failure）；
AttestGuard 三方法/§4.3 矩阵/consume 时机/config_invalid 节流、InstallFetcher 流程顺序（分钟→组合校验→
verify→decide→日窗口→consume→tx）、retryAfterSec、recover 决策 9、attestExisting（严格 installToken、
幂等跳过额度与消费、FOR UPDATE 404001、锁内重查、5 把 retire、冲突重查）、customer.json（39 条含 3 新 op +
attestationStatus）、core-job 三 job（回填 drain + already_used 放弃 + 退避递增 failure_count、刷新 bit0*2+bit1、
缺配置跳过）均核对通过。发现：

- ~~**[中] createInstall 并发同 keyId → 500000 而非 403001**~~ **已修（2026-10-05 本会话）**：`InstallFetcher.createInstall` 事务段包 try/catch `DataIntegrityViolationException` → 抛 `ApiError(ATTESTATION_FAILED)` 403001(key_reused)；新增单测 `concurrent unique-constraint conflict maps to 403001 not 500`（InstallFetcherAttestTest，35/35 绿）。
- [低] provider 120 在 ENFORCE 下返回 503002（NotEvaluated），且 verifyBundle 不检查 config.android
  是否配置；规格 §4.1 要求「未配置 provider 的 proof → INVALID(provider_not_configured) → ENFORCE 403001」。
  1a 无实际影响（客户端不发 120），1b 接入时改。
- [低] isChallengeEnabled 不检查 challengeCodec（secret 缺失 + 非 ENFORCE）→ enabled=true →
  issueChallenge 503002：最终一致，但客户端会白生成一把 key 再吃 503002。可在 enabled 里加 secret 判空。
- [低] config_invalid 节流按 projectId（规格是 projectId+configHash）：换一个错误配置后首条可能被节流。
- [低] event=install.attest 缺 signals 字段（§5.5）；recover 失败路径（404/403002/403001/503002）不打
  event=install.recover，日志平台聚合 not_found/blocked 比例做不到——建议失败路径也打结构化事件。
- [低] VerifiedProof.evidence 只有 {provider}（§5.2 预期「证书摘要」）——attestation_object 列已承载
  原始材料，evidence 恒为空壳；要么验签时放证书链摘要，要么改文档。
- [低] store_mismatch 判定逻辑在 Fetcher（provider==110 && storeType!=10），Guard.logStoreMismatch 不比较；
  1b 的 provider 120 交叉核对暂无调用方。逻辑对但分散，1b 时收拢进 Guard。

流程状态：T5/T6/T7/T11 服务端产物**已全部 commit**（`c8f1df6`，含 [中] key_reused 修复 + 156 个相关测试全绿；
GitNexus detect-changes 已跑：scope=all「No changes detected」）。**V15 已在真实库验证**（2026-10-05）：
临时库 `attest_v15_check` 全量 15/15 migrate + 索引/列 DDL 核对（uk_subject 部分唯一索引、4 个部分/普通索引、
sign_count/receipt 列默认值）+ `core_api_local` 增量单步 V15 成功；临时库已删。
注意：主 checkout（~/ai/myprojects/ifmix_server）已被切到 feature/api-key 分支（另一会话的 AI key 池工作，
含未提交的 e2e 删除——与 attest 无关，勿混淆）；feature/attest 在 worktree
/Users/jason/orca/workspaces/ifmix_server/attest。

### T11 文档同步（规格 §11 清单）

install.graphqls / customer.graphqls 描述（T5 顺带）、docs/design/install-tracking.md、docs/DATABASE.md、
docs/release.md（未发布变更：attestation 默认关、限流阈值调整、新 env 两个）、
antique/docs/install-tracking-frontend-api.md、antique/apps/shared/src/api/codes.ts 注释、
antique/docs/idempotency-frontend-api.md 的 refresh bind 更正（§11 已注明）。
**状态：done**（备注：antique 侧（前端部分，先前会话）：`docs/install-tracking-frontend-api.md` 按 shared 实际代码补全 createInstall proof/proofStatus/storeType/attestationStatus、§1.3 三个新 operation、§5.1 统一调度与退避（30s/2m/10m + retryAfterSec 优先）、§6.1 五个新错误码表（403001 replay/key_reused 不自动重试、429002 带 retryAfterSec、503002 进 isTransient）、allowlist 34→39 更正；`docs/CHANGELOG.md` 追加一期条目；`codes.ts` 仅 RATE_LIMITED 一行注释补充 retryAfterSec 说明；`idempotency-frontend-api.md` 核查后**无需改**（refresh 幂等 bind 描述与新实现一致）。**服务端部分（2026-10-05 本会话，随 T5/T7 完成）**：install.graphqls 全量描述（attest 一期 6 类型 + 3 mutation + 限流说明，T5 顺带落盘）；`docs/design/install-tracking.md` §4.1 改双层限流/attestationStatus/3 新 mutation + 新增 §4.7 attestation 节 + §7.5 契约与 schema 逐字同步；`docs/DATABASE.md` V14/V15 迁移记录 + 新增「Install 平台证明（V15）」节（core_install_attestation/store_type/app_attest_config）；`docs/release.md` 新增未发布 `feature/attest` 段（attestation 默认关、限流阈值调整、新 env 两个、core-job 三任务、发布顺序与 §9 检查清单引用）。antique 仓其余文档按用户「只做 ifmix_server」的澄清，留前端仓自行同步）

---

## 4. 执行顺序

```
T0（我，手改） → T1（我，跑命令）
T2 ∥ T3（两个 subagent 并行）
T4 ∥ T8（T2 完成后 T4 可开工；T8 只要规格即可更早）
T5（T2+T3+T4 完成）、T6（T2 完成即可）→ 并行
T7（服务端编译+测试，修复循环）
T9（T8 完成）→ T10
T11（收尾）
每个任务完成后：更新本文件状态 + 备注。
```

并行度控制：同一仓库内避免两个 agent 改同一个文件（T2 与 T3 都可能碰 InstallFetcher——
**约定：T2 不碰 Fetcher，InstallFetcher 的限流调用点由 T3 迁移、attest 流程由 T5 修改**；
T5 在 T3 之后）。

---

## 5. GitNexus 影响分析记录

（T1 完成后填写：符号 → 风险 → 结论）

- `checkFixedWindow`：**HIGH**（5 个受影响点：newScan / runDeepResearch / createAnonymousCustomer / InstallFetcher.createInstall 等）。对策：本次改动就是要删除它并在同一改动内迁移全部调用方（WP-B），风险闭环。
- `createInstall`：LOW（Fetcher→Facade→Handler 链各 1-2 个上游，全部在本次改动范围）。
- `ErrorCode`：**MEDIUM**（58 个受影响点）。对策：仅**新增**枚举项，不修改/删除任何现有项，纯增量。
- `mustGetTokenInstallId`：**CRITICAL**（14 个受影响点）。对策：**不改其签名与语义**（install 层限流的新代码直接读 `action.tokenInstallId`，绕开它；它的 legacy 行为原样保留）。
- 结论：三项 HIGH/CRITICAL 都不涉及修改既有行为，风险由计划本身消解，可以继续。

---

## 6. 验证命令速查

```bash
# 服务端
./gradlew :core-api:compileKotlin
./gradlew :core-job:compileKotlin
./gradlew :core-api:test
./gradlew :core-job:test
./gradlew :core-api:flywayMigrate   # 需要 PG；无库时至少确认迁移 SQL 语法评审通过

# 客户端（cd /Users/jason/ai/myprojects/antique）
pnpm --filter @ifmix/antique exec expo install @expo/app-integrity@57.0.2
# typecheck/test 命令进仓库后从 package.json scripts 确认，写回这里：
pnpm --filter @ifmix/app-shared run typecheck     # shared 层（已验证 0 错误）
pnpm --filter @ifmix/app-shared test              # shared 层（已验证 127 绿）
# apps/antique 的 typecheck/test 命令待 T9/T10 确认后补记
```

---

## 7. 交接注意事项

- 规格 §0 v5 修订表是本轮唯一新增的 review 轮次；实现若再遇规格含糊，**优先按 §2 已定决策**，
  仍含糊则在「备注」记录决定并继续（不要停下来等用户，除非破坏性/超出范围）。
- 不要把 fixture/真机相关测试当作阻塞项（明确 out of scope）。
- 1b（Play Integrity）只保留协议，不写任何服务端/客户端 Android 实现代码。
- 用户未要求 commit；工作停在「全部任务 done + 编译测试绿」。
- 若改动需要新建迁移文件，先 `ls core-api/src/main/resources/db/migration/` 确认最大版本号。

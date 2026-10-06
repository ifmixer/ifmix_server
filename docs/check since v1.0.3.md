# v1.0.3 → HEAD 文档一致性审计报告（v1.0.6 未发布增量）

- 工作区：`/Users/jason/orca/workspaces/ifmix_server/server-graphql-to-rpc`（分支 `feature/graphql-to-rpc`，工作树干净，HEAD=`43168044`）
- 对比基线：tag `v1.0.3`（`2a3bbf0`，2026-09-21），共 **135 个 commit**。
- 审计对象：`docs/ops/Changelog.md`（下称 Changelog）、`docs/ops/release.md`（下称 release）、`docs/design/**`（重点 wire-encryption.md / api-protocol / infra）。
- 日期：2026-10-06。严重度标记：**blocker**（发布前必须处理）/ **should-fix** / **nice-to-have**。

---

## A. 改动总览（v1.0.3..HEAD 实际功能性分组）

`git diff --stat`：core-api/src/main 217 文件 +9372/−3023；core-job/src/main +attest 3 job 与 cleaner（共 230 文件）。

| 功能域 | 代表 commit | 内容 |
|---|---|---|
| HTTP RPC 化（M0–M5） | `309d5cdb`、`5a1225ba`、`eb9e46ff`、`239943c6`、`bf8712dc` | GraphQL(DGS) 引擎删除，全部 action 迁 `POST /api/customer/core/{actionName}`；8 个 controller（auth/install/customer/cs/media/pay/ai/demo）；persisted-queries/schema 全删；`build.gradle.kts` 删 DGS codegen |
| RPC 协议收敛 | `dc086454`、`b61f106f`、`f28c3eb2`、`4280f26e`、`19c4ba85` | ActionSpec 撤销→controller 常量；`ApiRequestBody<X>` 泛型化；`Envelope.ok(reqId,data)`；DTO Dto 后缀；**action 名两轮重命名**（`19c4ba85`：demo/media/auth install 名） |
| install 体系 + attestation 一期 | `acdc87f9`..`2ee7d200`（feature/install）、`d823692b`..`c8f1df68`（feature/attest）、`6cb60c7e` | `core_install`/relation（V5/V6）、token `type`/`iid` claim、createIos/createAndroidInstall、challenge/recover/attest；V14/V15；**三个 core-job attest job 入口整体注释（不调度）** |
| AI 异步化 + key 池 | `1160a24e`、`b9747ca2`..`ca00fd6d`、`5a25c260`、`4cb63458` | scan/DR 异步任务（V10–V13）、FCM push（`FirebaseAppRegistry`、i18n `messages*.properties` 17 语）、配额台账（V4/V9）、key 池+冷却（V8 表改名） |
| wire 加密 | `b17a16e2`、`2b63f3e9`（v3 HPKE 落地）、`081e3b62`（**版本 3→2 定稿**）、`62303a93` | RFC 9180 HPKE（BouncyCastle）、`x-wirep-version: 2`、400003/400004、required 模式无明文降级 |
| 安全/加固 | `50fafb95`、`2feca2f6`、`e5af85db`..`7cba9164`（docs） | Google webhook 共享 token（`GOOGLE_WEBHOOK_TOKEN`，缺省 403）、RefreshToken SecureRandom、JWT 缺失 fail-fast |
| Konvert 引入 | `b2038bdb`（K0/cs 试点）、`11e5debf`（K2 demo）、`9d5df091`（K3 ai）、`744b5889`（K4 auth）、`ee772d03`（**文档定稿\"弃 Konvert\"**，随后被 K2–K4 反转） | KSP `io.mcarle:konvert:4.5.1`；demo/ai/auth 出参 mapper 生成；pay/media/cs 出参保留手写 |
| 其他 | `f28c3eb2`、`a6e66be2`、`4cd41997`、`43168044` | Spring 边界反序列化、reqId、日志 JSON、`q_ai_scan_getById` `include` 参数 |
| DB 迁移 | V4–V16 全部新增（`core-api/src/main/resources/db/migration/`） | 见 §D |
| 新依赖（core-api/build.gradle.kts） | bcprov-jdk18on:1.86、firebase-admin:9.7.0、konvert 4.5.1、java-uuid-generator、webauthn4j-appattest:0.30.1 | DGS/graphql-dgs 全删 |
| 配置面（application*.yml） | 新增 `app.wire-crypto.*`、`app.pay.google-webhook-token`、`app.ai.*`（timeout/deadline/keypool）、`app.ratelimit.*`（全 action 阈值）、`app.attest.*`、`spring.messages`（i18n）、`spring.data.redis.timeout: 500ms`、`SPRING_PROFILES_ACTIVE` 默认 local 兜底 | 新 env：`WIRE_CRYPTO_KEYS`、`WIRE_MAX_REQUEST_BYTES`、`WIRE_MAX_DECOMPRESSED_BYTES`、`GOOGLE_WEBHOOK_TOKEN`、`APP_ATTEST_GLOBAL_ENABLED`、`APP_ATTEST_CHALLENGE_SECRET`、`APP_ATTEST_VERIFY_PERMITS`、`AI_CALL_TIMEOUT_SEC`、`AI_SCAN_DEADLINE_SEC`、`SCAN_PROMPT_VERSION`、`PG_*_MIN_IDLE` |

---

## B. Changelog 缺口 / 过时（逐条：文档说 X，代码做 Y）

### B1. install/attestation action 名全部过时（**blocker**）
- Changelog L11：`m_install_createAttestChallenge / m_install_recoverInstall / m_install_attestExisting`；L10：`m_install_createInstall / m_install_updateInstall`。
- 代码实际（`bff/api/customer/auth/InstallApiController.kt`，`19c4ba85` 改名后）：`m_auth_install_createIosInstall`、`m_auth_install_createAndroidInstall`、`m_auth_install_updateOne`、`m_auth_install_createAttestChallenge`、`m_auth_install_recover`、`m_auth_install_attest`。
- release L48 同样写 `m_install_createAttestChallenge / m_install_recoverInstall / m_install_attestExisting` —— 三处旧名，客户端会照文档实现错误路由。
- Changelog L19 只记录了 create 的平台拆分，未记录 recover/attest/updateOne/createAttestChallenge 的最终名。

### B2. `m_customer_deleteAccount` 名过时（**should-fix**）
- Changelog L16 写 `m_customer_deleteAccount`；实际常量 `REQNAME_DELETE_ACCOUNT = \"m_auth_account_deleteOne\"`（`AuthApiController.kt:59`，`dc086454` 改名）。

### B3. DB 迁移行漏 V16 且停机清单不完整（**blocker**，与 §D 联动）
- Changelog L39「DB 迁移」止于 V15，且「V6/V8 要求停机窗口内先迁移后发代码」。V16（`core_media_upload_record → core_media_file_record`）不在该行；而 v1.0.3 的 `UploadRecord.kt` 是 `@Table(\"core_media_upload_record\")`，迁移后旧实例读/写直接失败 —— **V16 与 V6/V8 同属\"必须停机\"迁移**，文档漏列。
- release L38「V4–V15/16，以发布时实际为准」含糊；L68（feature/install 小节）仍写「先 flywayMigrate（V4–V9）」，与 V10–V16 现实不符。

### B4. wire 加密 bullet 与最终代码语义基本一致，但漏了 env（**should-fix**）
- Changelog L14 正确写了 `x-wirep-version: 2`、400004（明文拒）/400003（解密失败）、5MB 上限，但未列 `WIRE_CRYPTO_KEYS` / `WIRE_MAX_DECOMPRESSED_BYTES`；算法表述「X25519+HKDF+AES-256-GCM」未提\"最终实现是 RFC 9180 HPKE（BouncyCastle），非手写 HKDF\"（`WireCrypto.kt:12-17`）。
- **`WIRE_CRYPTO_KEYS` 缺失时 required 模式下 `/api/**` 全灭（400003/400004）——release L41 的「env 核对」步骤只列 `AUTH_JWT_PRIVATE_KEY` / `APP_ATTEST_GLOBAL_ENABLED` / `APP_ATTEST_CHALLENGE_SECRET`，漏了 `WIRE_CRYPTO_KEYS`（blocker 级运维风险：忘记配置 = 全量接口不可用，且\"大声失败\"是设计）。**

### B5. `GOOGLE_WEBHOOK_TOKEN` / `/webhooks/iap/google` 鉴权收紧（**blocker** 运维面）
- 代码（`WebhookController.kt:110-133`）：token 未配置时端点一律 403（此前端点**完全无鉴权**，公网可写通知表）；token 走 `?token=xxx` 或 `X-Webhook-Token`，恒定时间比较。
- Changelog 与 release 均未提及：新 env `GOOGLE_WEBHOOK_TOKEN`（prod env 清单 + release 步骤 6 都缺）。上线后若不把 token 配进 Pub/Sub push URL，IAP 订阅状态静默停摆。
- 附带：该 token 经 query string 进 `RequestLoggingFilter` 的 `path` 字段（`uri + query`，L84）——即 P1 日志脱敏项的一部分，文档需说明。

### B6. `q_ai_scan_getById` `include` 参数（已记录，一致）
- Changelog L17 与代码（`AiInputs.kt` `FindScanByIdInput.include` + `requireScanInclude`，commit `43168044`）一致。✔

### B7. DR/scan 同步返回的 `errorCode` 字段（**nice-to-have**）
- `m_ai_deepResearch_run` 现在同步返回任务 id + `errorCode`（`TASK_SUBMISSION_FAILED` 免二次查询，commit `37698b3b` 定稿）；`AiTaskErrorCodes`（AI_FAILED/TIMEOUT/TASK_SUBMISSION_FAILED/INTERNAL_ERROR）对客户端可见。Changelog「AI 扫描/DeepResearch 异步化」bullet（L12）未提该出参字段与码表。

### B8. i18n 推送文案（**nice-to-have**）
- 新增 `spring.messages` 绑定 + 17 个 `i18n/messages*.properties`（`2feca2f6`/`50fafb95`）：push 通知文案本地化。Changelog L12 只写了「完成后 FCM push」，未提文案 i18n 机制与 locale 集（ar/bg/da/el/hr/ro/sv 等超出 2026-09-10 条的 10 语言归一集——归一集不变，i18n 资源集更大，两者无冲突但值得注一句）。

### B9. 其他未入 Changelog 的行为变化（**nice-to-have**）
- `SPRING_PROFILES_ACTIVE` 默认兜底 `local`（`application.yml`，原无默认值缺 env 即启动失败）——本地/CI 行为变化，运维需知。
- Redis Lettuce 命令超时 500ms（key 池/限流快速降级放行）——限流\"降级放行\"语义不变但触发更快。
- 请求 body 全局 5MB 上限对**明文**也生效（`WireCryptoFilter` Content-Length 预检，`/api/**` 明文/密文统一 400000）——L14 只说\"超限 400000\"未点明明文也拦。

---

## C. wire 版本一致性（高优先）

**最终代码事实**（以 `core-api/.../infra/http/` 为准）：

| 项 | 代码实际值 | 位置 |
|---|---|---|
| 版本号 | **2**（`WIRE_VERSION_VALUE = \"2\"`、`WireCrypto.VERSION = 2`） | `WireCryptoFilter.kt:152`、`WireCrypto.kt:120` |
| 头名 | `x-wirep-version`（`RequestHeaders.WIREP_VERSION`） | `RequestHeaders.kt:22` |
| Suite | RFC 9180 HPKE base：DHKEM(X25519, HKDF-SHA256) + AES-256-GCM，BouncyCastle `org.bouncycastle.crypto.hpke`，**无手写常数/黑名单** | `WireCrypto.kt:12-17,138` |
| 线格式 | 请求 `ver(1)=2|kid(1)|enc(32)|flags(1)|HPKE-Seal‖tag`，AAD=前 35B；响应 `flags|nonce(12)|AES-GCM‖tag`，resKey=`HPKE-Export(\"ifmix-wire-v2-res\",32)`；gzip 阈值 4096，解压上限 1MB | `WireCrypto.kt:19-30` |
| 400004 | `WIRE_REQUIRED`：required 模式下 `/api/**` 明文或 `x-wirep-version` 版本不符（**原 ts 时效预留码改作此用**） | `ErrorCode.kt:18-20` |
| 400003 | `WIRE_DECRYPT_FAILED`：解密失败/kid 未知/解压超限，**keys 为空时加密请求也 400003**（大声失败） | `ErrorCode.kt:18`、`WireCrypto.kt:146` |
| 明文降级 | **无**：required 模式明文一律 400004；optional 仅 local/dev（`application-local.yml` `mode: optional`） | `WireCryptoFilter.kt:70-87`、`application-prod.yml` |

历史：`2b63f3e9` S1 落地\"wire v3 HPKE + x-wirep-version（35B AAD）\"→ `081e3b62`「强制加密定稿：版本 3→2、无版本协商、required/optional 模式」。版本号从 3 回退为 2，头名保留 `x-wirep-version`。

**文档冲突清单**：

1. **`docs/design/infra/wire-encryption.md` §10 整体过时（blocker）**：§10 定稿为 **v3**（`ver(1)=3`、`info=\"ifmix-wire-v3\"`、`\"ifmix-wire-v3-res\"`、env `APP_WIRE_KEYS`、\"v3 直接取代 v2，明文 v1 降级保留\"、\"无 `x-proto-version: 3` 的请求按 v1 放行\"）。最终代码是 **v2 + `ifmix-wire-v2` + `WIRE_CRYPTO_KEYS` + required 无明文**。**info 字符串与版本号是逐字节协议字段——若客户端仓库按 §10/`wire-v3-plan-*.md` 实现过 `ifmix-wire-v3` 或 ver=3，联调必然全量解密失败**；需与客户端 `wireCrypto.ts`（§10.4 标 ✅ 完成）核对实际落点用的是 2/`ifmix-wire-v2`（`62303a93` 称客户端 b0d000e 已落地 fail-fast/降级删除，推测已对齐，但必须验证）。文件头「状态：v2 已实现但**未发布**；v3 提案已评审定稿」两句话在 2026-10-06 全部失效。
2. **`docs/design/infra/wire-v3-plan-server.md` / `wire-v3-plan-client.md`（should-fix）**：仍按 v3 + `APP_WIRE_KEYS` + 「版本判断从 2 改为 3」写。这两个是 §10.4 的实施计划，最终被 `081e3b62` 的 3→2 定稿改写，但文档未回写。
3. **代码注释过时（should-fix）**：
   - `RequestHeaders.kt:19-20`：「缺省/1 = 明文；**3** = body 加密… 响应头同名回传 **3**」——实际是 2；「旧名 x-proto-version（v2 从未上线）已随 v2 一并移除，**v3 用新名 x-wirep-version**」——历史叙事停在 3 时代。
   - `application.yml:64`：「空 = 不支持 **v3**（v3 请求返回 400003，**客户端自动降级明文**）」——版本号错；且\"降级明文\"在 required 模式下不成立（明文是 400004 被拒，不是放行）。
   - `application-prod.yml` 注释同病：「缺省 = 不支持 v2，v2 请求全部 400003，客户端自动降级明文」——版本说对了，\"自动降级明文\"错（required + 无降级定稿于 `081e3b62`/`62303a93`）。
   - `WireCrypto.kt:33`：向量目录名 `wire-v3` 已注明\"历史遗留\"，OK。
4. Changelog L14 本身**正确**（v2/400004/400003/无协商），是唯一与代码一致的 wire 描述。✔
5. wire-encryption.md §9「已决策：保留降级与 kill switch」与最终 required 无明文降级定稿**矛盾**（§9 是\"v2 时代\"结论，被 `081e3b62` 的定稿推翻但没改）。应加修订注记（nice-to-have，因 §10 头声明\"§9 对 v3 继续有效\"）。

---

## D. 迁移顺序 / 发布计划正确性

逐文件核实（`db/migration/`）：

| 迁移 | 对旧实例的影响 | 计划是否覆盖 |
|---|---|---|
| V6 `install_id` varchar→uuid + core_install PK 收敛 | **旧代码写 varchar installId 即失败** → 必须停机先迁 | ✔（Changelog L39 / release L38、L68） |
| V8 `core_ai_agnes_key`→`core_ai_api_key` | 旧代码查旧表名即失败 → 必须停机先迁 | ✔（同上） |
| **V16** `core_media_upload_record`→`core_media_file_record` | v1.0.3 `UploadRecord` 实体绑旧表名 → **同样必须停机先迁** | **✘ 漏列**（Changelog DB 行止于 V15；release 只写 V6/V8；「V4–V15/16」措辞含糊）。`19c4ba85`（10-06）才新增 V16，两份文档写于其前。 |
| **V12** `ai_scan_record.status` 语义改写（旧 20=完成/30=失败 → 新 20=IN_PROGRESS/30=SUCCESS/40=FAILED，含存量行 UPDATE） | 旧代码写 20/30 会被新代码误读为 IN_PROGRESS/FAILED——混合写期与**回滚**场景危险 | ✘ 未在\"必须先停机\"清单中（停机+先迁的发布顺序下可接受，但应补一句 V12 回滚窗口风险） |
| V4/V5/V7/V9/V10/V11/V13/V14/V15 | 加列/加表/加索引（V5 含 UNIQUE 索引、V11 加 jsonb 列…），旧代码可写 | 无停机要求，✔ |

- release L68「先 flywayMigrate（V4–V9）」是 feature/install 小节的历史文案，在合并后的 v1.0.6 计划中仍是误导（实际 V4–V16）。**should-fix。**
- core-job attest 三个 job「决定不调度」：代码事实 ✔（`AttestJobs.kt:25-78` 三个 `@Bean` 整体注释，`--job.name=attest*` 退出码 2 拒绝；cleaner 保留可单测）；release 决策 3 + L52 已如实记录 ✔。**`release.md` 一致，Changelog 未提 core-job（其口径\"记录 core-api 面向客户端/DB 的变更\"可接受，但 V15 表是 job 依赖的——建议加一句说明）。**
- `:core-job:test` 在 release 步骤 1 的全量测试命令中 ✔。

---

## E. P1 修复项现状（release L25-30「2026-10-06 复核：仍未修」逐条核实）

| P1 | 复核结果 |
|---|---|
| **WireCrypto 低阶点黑名单** | **已失效/obsolete**：代码里根本不存在黑名单（`grep 低阶/LOW_ORDER` 仅命中测试注释）。S1 起改用 BouncyCastle RFC 9180 HPKE，低阶点由 HPKE 库在 DHKEM 验证时拒绝（`WireCryptoTest.kt:169`「全零 X25519 低阶点：HPKE 库拒绝」）。「黑名单常数错误」针对的是已不存在的旧手写实现。→ release L26 的「仍未修」是**错误结论**，应改为 obsolete（连同 Changelog L32 的「未修（代码无低阶点校验）」一并修订）。小瑕疵：`WireCryptoTest.kt:170` 该用例 payload 首字节写的是 `3`（旧 v3 版本号残留），实际先触发 version 校验而非低阶点路径——nice-to-have。 |
| **ClientIpResolver 可信 IP** | **仍 open**：`ClientIpResolver.kt` 仍 `X-Forwarded-For` 取第一段，无 `CF-Connecting-IP`/可信代理链；所有 `:ip:` 限流 key 可伪造绕过。与文档一致。 |
| **日志脱敏 refreshToken/authCode** | **仍 open**：RPC 后 refresh 凭证在**加密 body 的 input**（`AuthApiController.kt:94-100` `RefreshInput.refreshToken`），wire 过滤后是明文；`RequestLoggingFilter.kt:88` 4xx/5xx WARN 原样打 `req`（截 2000）→ refreshToken 明文进日志。`logout` 同（L113）。Google webhook token 经 `?token=` 进 `path`（L84）。与文档一致。 |
| **AI 惰性超时 300 vs 600** | **仍 open**：`ScanAggHandler.kt:425` `STALE_IN_PROGRESS_SEC = 300L`，`application.yml` `AI_SCAN_DEADLINE_SEC:600`。300–600s 的在途任务会被查询侧误判 TIMEOUT。与文档一致。 |
| **DR 空 premium_result 判失败** | **仍 open**：`DeepResearchTaskService.kt:81` 注释「AI 正常返回即任务 SUCCESS；scan_status 原样保存在 basicResult」，无 `premium_result` 非空校验；release 决策 6（「缺 premium_result 算失败」）未落地。与文档一致。 |

**P2 抽查**：
- RateLimiter INCR/EXPIRE 原子化：**仍 open**（`RateLimiter.kt:72-86` `increment` 与 `expire` 分离）。
- webhook 幂等唯一索引：**仍 open**（`pay_store_notification` 只有非 unique 的 `store_notif_platform_token_idx`（V1 baseline:775），去重靠应用层 `existsByPlatformAndToken`（`PaymentWebhookHandler.kt:41`），存在竞态）。
- FirebaseAppRegistry 负缓存：**已修**（`FirebaseAppRegistry.kt` null 哨兵 `Optional` 缓存，注释明示\"缓存无配置结论\"）。
- AiChatClientFactory 无淘汰缓存：**仍 open**（`AiChatClientFactory.kt:33` 裸 `ConcurrentHashMap`）。
- scan 表 customer_id 索引：**仍 open**（`ai_scan_record` 仅 `scan_record_project_id_idx`）。
- bind 关系唯一索引：V5 建了 `core_install_customer_rel_uq`（全量 unique，install_id+customer_id）——与 P2 所指是否同一项待确认（若 P2 要的是带 deleted_at 的 partial 索引则仍 open）。

**结论：release 的\"仍未修\"复核中，4/5 准确；WireCrypto 低阶点项应改判 obsolete（should-fix，避免误导后续安全声明）。**

---

## F. design 文档矛盾（Konvert 为主线）

**代码最终事实**（2026-10-06）：
- `core-api/build.gradle.kts:66-67`：Konvert 4.5.1 KSP 已长期挂上。
- demo 出参：`dto/demo/DemoKonvertMappers.kt`（`@Konverter`，KSP 生成 `DemoKonvertMappersImpl`，多参聚合按\"constant 占位 + 调用方 copy\"回退，`DemoQueryService.assemble()`）。
- ai 出参：`dto/ai/AiKonvertMappers.kt`（K3，`basicResult`/`latestDeepResearch` constant null 规避稀疏实体）。
- auth 出参：`dto/auth/AuthKonvertMappers.kt`（K4；MeRes 组装保留手写）。
- cs 入参：`@KonvertTo`（K0 试点）；**media、pay 出参保留手写**（`PayController.kt:55` `expiresAt` 带条件转换；media presign 手写 DTO）——与 `konvert-rollout-server.md` §5.3 判定一致。

**文档矛盾**：
1. **`docs/design/api-protocol/rpc-protocol-decisions.md` L54（现行文档）：「默认不引入 Konvert 做实体映射」——与最终代码相反（Konvert 用于 demo/ai/auth 实体→Res 映射）**（should-fix）。§5「三层分工」的第 2 层\"聚合/协议→手写 companion factory\"部分仍成立（MeRes、pay），但\"纯投影→Jimmer DTO 生成，零手写\"被 Konvert 生成替代。该文件被 archive/README 标为\"现行协议与编码规则\"，需加 Konvert 例外条款或指向 `konvert-rollout-server.md`。
2. **`docs/design/api-protocol/archive/rpc-rollout-server.md` §3.2「mapper 规则：弃 Konvert」与 archive/README.md 第 5 行「`konvert-rollout-server.md`（K3/K4 待实施）」——后者已被 `9d5df091`/`744b5889`/`c254ed33` 完成并打回\"待实施\"，README 未更新**（nice-to-have，archive 内）。
3. 叙事链条：`ee772d03`（文档定稿\"弃 Konvert\"，三层分工）→ `b2038bdb`/`11e5debf`/`9d5df091`/`744b5889`（实际逐模块改回 Konvert 生成，`konvert-rollout-server.md` 全阶段完成回写）→ 现行 `rpc-protocol-decisions.md` 仍停在\"弃 Konvert\"。git log 同时存在两套定稿，**最终真值 = Konvert 用于纯投影/出参（demo/ai/auth/cs 入参），手写保留在聚合与含逻辑分支（pay、MeRes、attest 判定）**；文档未收口（should-fix）。
4. wire 系列文档见 §C1/C2（`wire-encryption.md` §10、`wire-v3-plan-{server,client}.md` 停在 v3 叙事）。

---

## G. 建议补进 Changelog / release 的条目（copy-paste 就绪）

**Changelog → Added 区（v1.0.6 段）：**

- **install/attest action 最终名修正（2026-10-06，`19c4ba85`）**：`m_auth_install_createIosInstall` / `m_auth_install_createAndroidInstall` / `m_auth_install_updateOne` / `m_auth_install_createAttestChallenge` / `m_auth_install_recover` / `m_auth_install_attest`（本表 L10/L11 的 `m_install_*` 与 `m_install_recoverInstall`/`m_install_attestExisting` 均作废）；`m_customer_deleteAccount` → `m_auth_account_deleteOne`。
- **新 env（本节遗漏）**：`WIRE_CRYPTO_KEYS`（`kid:base64` 多 kid；缺省 = 不支持加密，required 模式下 `/api/**` 全 400003/400004，启动即大声失败）、`WIRE_MAX_REQUEST_BYTES`（默认 5242880）、`WIRE_MAX_DECOMPRESSED_BYTES`（默认 1048576）、`GOOGLE_WEBHOOK_TOKEN`（缺省 `/webhooks/iap/google` 一律 403，IAP 通知整体关闭——**上线前必须配置并同步 Pub/Sub push URL**）、`AI_CALL_TIMEOUT_SEC`（360）、`AI_SCAN_DEADLINE_SEC`（600）、`APP_ATTEST_VERIFY_PERMITS`（32）。
- **Google IAP webhook 鉴权收紧（breaking，运维）**：`/webhooks/iap/google` 需共享 token（`?token=` 或 `X-Webhook-Token`，恒定时间比较）；未配置 = 端点关闭（403）。旧端点此前无鉴权。
- **wire 最终版定稿说明**：线协议版本 = **2**（`x-wirep-version: 2`，RFC 9180 HPKE，BouncyCastle 标准实现；历史 3 草案经「3→2 定稿」commit 合并为 2，无版本协商）；400004 = 明文/版本不符（`/api/**` 无明文降级），400003 = 解密失败（含 keys 未配）；ts 偏差仅 warn。
- **`m_ai_deepResearch_run` 同步返回 `errorCode`（`TASK_SUBMISSION_FAILED` 等，`AiTaskErrorCodes` 码表）**：提交失败可同步感知，无需二次轮询。
- **push 通知文案 i18n**：`spring.messages`（`i18n/messages*.properties`，17 locale，缺省回退英文）。
- **启动兜底**：`SPRING_PROFILES_ACTIVE` 缺省回落 `local`（原缺省启动失败）；Redis 命令超时 500ms（限流/key 池快速降级）。
- ~~Fixed 区~~：「WireCrypto 低阶点黑名单」改判 **obsolete**（实现已换 RFC 9180 HPKE，低阶点由 BouncyCastle 库拒绝，无手写黑名单可修）。

**Changelog → DB 迁移行修订：**

- V4→V5/V6→V7→V8→V9→V10–V13→V14→V15→**V16（media 表 RENAME）**；**\"必须停机先迁\"清单 = V6 / V8 / V16**（旧代码引用旧表名/旧列类型即失败）；V12 另含 `ai_scan_record.status` 语义改写（20/30 换位 + 存量回写），**发布后回滚窗口内勿回退旧代码**。

**release → 修订：**

- 步骤 3：「flywayMigrate（V4–V16）」；「必须先停机再迁移」处补 **V16**（并注 V12 状态语义改写的回滚风险）。
- feature/install 小节「V4–V9」改为「V4–V16（本小节仅 install 域 V4–V6 + 台账 V9）」。
- 步骤 6 env 核对补：`WIRE_CRYPTO_KEYS`（required 模式强依赖）、`GOOGLE_WEBHOOK_TOKEN`（IAP 通知开关）。
- P1 清单：WireCrypto 低阶点项改判 obsolete（含一句\"测试 `WireCryptoTest` 低阶点用例依赖 HPKE 库拒绝，并修掉用例中残留的 v3 版本字节\"）。
- §C 文档核对项（发布前人工 1 条）：**客户端 `wireCrypto.ts` 必须使用版本号 2 + `ifmix-wire-v2` / `ifmix-wire-v2-res` 上下文**（若按 `wire-encryption.md §10` 的 v3 文案实现过则全量解密失败；`62303a93` 记录客户端 b0d000e 已对齐 fail-fast，需最终确认）。

**design 文档修订（should-fix 一批）：**

- `wire-encryption.md`：头部状态改为「v2（原 v3 草案，3→2 定稿）已实现；required 无明文降级」；§10 全文加\"已被 3→2 定稿改写\"注记（版本号、info 串、env 名三处）；§9 降级结论加修订注记。
- `wire-v3-plan-server.md` / `wire-v3-plan-client.md`：加「v3 定稿为 v2 + WIRE_CRYPTO_KEYS」状态头。
- `rpc-protocol-decisions.md` §5：补「Konvert 例外条款：纯投影出参由 Konvert 生成（demo/ai/auth，见 konvert-rollout-server.md K2–K4 完成记录），聚合/含逻辑分支保留手写」。
- `api-protocol/archive/README.md`：`konvert-rollout-server.md` 状态改为「已实施（K2 11e5debf / K3 9d5df091 / K4 744b5889）」。
- 代码注释修订：`RequestHeaders.kt:19` 版本「3」→「2」；`application.yml:64` 与 `application-prod.yml` 的\"客户端自动降级明文\"→\"required 模式无明文降级（400004）\"。

---

## 重要发现 Top 15（回给调用方）

1. **[blocker]** install/attest 六个 action 在 Changelog L10/L11 与 release L48 全部过时（`m_install_*`→`m_auth_install_*`，`recoverInstall`→`recover`、`attestExisting`→`attest`），`m_customer_deleteAccount`→`m_auth_account_deleteOne`（`19c4ba85`/`dc086454` 后未回写）。
2. **[blocker]** V16（media 表 RENAME，`19c4ba85` 新增）不在「必须停机先迁移」清单（现为 V6/V8）与 Changelog DB 迁移行（止于 V15）；旧实例迁移后读旧表名即失败。
3. **[blocker]** `WIRE_CRYPTO_KEYS` 未进 release 步骤 6 env 核对；required 模式下缺配 = `/api/**` 全灭，而这是设计上的\"大声失败\"。
4. **[blocker]** `GOOGLE_WEBHOOK_TOKEN` 缺失时 IAP webhook 一律 403（`WebhookController.kt:110-122`，端点此前**完全无鉴权**）——Changelog/release 均未提，上线漏配 = IAP 静默停摆。
5. **[blocker]** `wire-encryption.md §10` + `wire-v3-plan-{server,client}.md` 仍定稿在 **v3**（`ifmix-wire-v3` info、`APP_WIRE_KEYS`、ver=3），最终代码是 **v2 + `WIRE_CRYPTO_KEYS` + `ifmix-wire-v2`**；客户端若按 v3 文案实现则全量解密失败，需人工核对客户端实际实现（`62303a93` 称已对齐，未验证）。
6. **[should-fix]** `RequestHeaders.kt:19` 注释仍写\"3 = body 加密、回传 3\"；`application.yml`/`application-prod.yml` 注释写\"客户端自动降级明文\"——与 required 无明文定稿（`081e3b62`）矛盾。
7. **[should-fix]** release P1 复核「2026-10-06 仍未修」中 **WireCrypto 低阶点项已 obsolete**（手写黑名单不存在了，BouncyCastle HPKE 库拒绝低阶点）；另 4 项（ClientIpResolver 仍取 XFF 首段、RequestLoggingFilter WARN 打明文 refreshToken、STALE=300<600、DR 空 premium_result 判成功）核实**确实仍未修**。
8. **[should-fix]** release L68「V4–V9」与步骤 3「V4–V15/16」两处迁移范围表述与实际 V4–V16 不符。
9. **[should-fix]** V12 改写 `ai_scan_record.status` 语义（20/30 换位 + 存量 UPDATE），回滚窗口风险未在发布计划中提及。
10. **[should-fix]** `rpc-protocol-decisions.md` L54「默认不引入 Konvert」与最终代码（demo/ai/auth 出参 Konvert 生成，`11e5debf`/`9d5df091`/`744b5889`）矛盾；git log 中「弃 Konvert」（`ee772d03`）与「换 Konvert」（K2–K4）两套定稿并存，未收口。
11. **[nice-to-have]** `api-protocol/archive/README.md` 仍写 konvert-rollout-server「K3/K4 待实施」，实际已完成（`c254ed33` 回写）。
12. **[nice-to-have]** `WireCryptoTest.kt:170` 低阶点用例 payload 首字节残留 `3`（v3 时代），实际先被 version 校验拦下，未真正验证低阶点路径。
13. **[nice-to-have]** Changelog 未记：`m_ai_deepResearch_run` 同步 `errorCode` 出参（`AiTaskErrorCodes` 码表）、i18n push 文案 17 locale、`SPRING_PROFILES_ACTIVE` 默认 local 兜底、Redis 500ms 命令超时。
14. **[已核实一致 ✔]** core-job 三个 attest job 入口整体注释（不调度）与 release 决策 3/L52 一致；Changelog wire bullet（v2/400003/400004/无协商/5MB）与代码一致；`q_ai_scan_getById` include 参数（`43168044`）与 Changelog L17 一致。
15. **[nice-to-have]** P2 抽查：RateLimiter INCR/EXPIRE 分离、webhook 无唯一索引、AiChatClientFactory 无淘汰缓存、scan 表无 customer_id 索引——仍 open；FirebaseAppRegistry 负缓存**已修**。

完整报告：`.superpowers/sdd/v103-audit/report.md`
"}}]},"wireToolInputs":{"call_7081b45c0df6489c807147c2":{"content":"# v1.0.3 → HEAD 文档一致性审计报告（v1.0.6 未发布增量）

- 工作区：`/Users/jason/orca/workspaces/ifmix_server/server-graphql-to-rpc`（分支 `feature/graphql-to-rpc`，工作树干净，HEAD=`43168044`）
- 对比基线：tag `v1.0.3`（`2a3bbf0`，2026-09-21），共 **135 个 commit**。
- 审计对象：`docs/ops/Changelog.md`（下称 Changelog）、`docs/ops/release.md`（下称 release）、`docs/design/**`（重点 wire-encryption.md / api-protocol / infra）。
- 日期：2026-10-06。严重度标记：**blocker**（发布前必须处理）/ **should-fix** / **nice-to-have**。

---

## A. 改动总览（v1.0.3..HEAD 实际功能性分组）

`git diff --stat`：core-api/src/main 217 文件 +9372/−3023；core-job/src/main +attest 3 job 与 cleaner（共 230 文件）。

| 功能域 | 代表 commit | 内容 |
|---|---|---|
| HTTP RPC 化（M0–M5） | `309d5cdb`、`5a1225ba`、`eb9e46ff`、`239943c6`、`bf8712dc` | GraphQL(DGS) 引擎删除，全部 action 迁 `POST /api/customer/core/{actionName}`；8 个 controller（auth/install/customer/cs/media/pay/ai/demo）；persisted-queries/schema 全删；`build.gradle.kts` 删 DGS codegen |
| RPC 协议收敛 | `dc086454`、`b61f106f`、`f28c3eb2`、`4280f26e`、`19c4ba85` | ActionSpec 撤销→controller 常量；`ApiRequestBody<X>` 泛型化；`Envelope.ok(reqId,data)`；DTO Dto 后缀；**action 名两轮重命名**（`19c4ba85`：demo/media/auth install 名） |
| install 体系 + attestation 一期 | `acdc87f9`..`2ee7d200`（feature/install）、`d823692b`..`c8f1df68`（feature/attest）、`6cb60c7e` | `core_install`/relation（V5/V6）、token `type`/`iid` claim、createIos/createAndroidInstall、challenge/recover/attest；V14/V15；**三个 core-job attest job 入口整体注释（不调度）** |
| AI 异步化 + key 池 | `1160a24e`、`b9747ca2`..`ca00fd6d`、`5a25c260`、`4cb63458` | scan/DR 异步任务（V10–V13）、FCM push（`FirebaseAppRegistry`、i18n `messages*.properties` 17 语）、配额台账（V4/V9）、key 池+冷却（V8 表改名） |
| wire 加密 | `b17a16e2`、`2b63f3e9`（v3 HPKE 落地）、`081e3b62`（**版本 3→2 定稿**）、`62303a93` | RFC 9180 HPKE（BouncyCastle）、`x-wirep-version: 2`、400003/400004、required 模式无明文降级 |
| 安全/加固 | `50fafb95`、`2feca2f6`、`e5af85db`..`7cba9164`（docs） | Google webhook 共享 token（`GOOGLE_WEBHOOK_TOKEN`，缺省 403）、RefreshToken SecureRandom、JWT 缺失 fail-fast |
| Konvert 引入 | `b2038bdb`（K0/cs 试点）、`11e5debf`（K2 demo）、`9d5df091`（K3 ai）、`744b5889`（K4 auth）、`ee772d03`（**文档定稿\"弃 Konvert\"**，随后被 K2–K4 反转） | KSP `io.mcarle:konvert:4.5.1`；demo/ai/auth 出参 mapper 生成；pay/media/cs 出参保留手写 |
| 其他 | `f28c3eb2`、`a6e66be2`、`4cd41997`、`43168044` | Spring 边界反序列化、reqId、日志 JSON、`q_ai_scan_getById` `include` 参数 |
| DB 迁移 | V4–V16 全部新增（`core-api/src/main/resources/db/migration/`） | 见 §D |
| 新依赖（core-api/build.gradle.kts） | bcprov-jdk18on:1.86、firebase-admin:9.7.0、konvert 4.5.1、java-uuid-generator、webauthn4j-appattest:0.30.1 | DGS/graphql-dgs 全删 |
| 配置面（application*.yml） | 新增 `app.wire-crypto.*`、`app.pay.google-webhook-token`、`app.ai.*`（timeout/deadline/keypool）、`app.ratelimit.*`（全 action 阈值）、`app.attest.*`、`spring.messages`（i18n）、`spring.data.redis.timeout: 500ms`、`SPRING_PROFILES_ACTIVE` 默认 local 兜底 | 新 env：`WIRE_CRYPTO_KEYS`、`WIRE_MAX_REQUEST_BYTES`、`WIRE_MAX_DECOMPRESSED_BYTES`、`GOOGLE_WEBHOOK_TOKEN`、`APP_ATTEST_GLOBAL_ENABLED`、`APP_ATTEST_CHALLENGE_SECRET`、`APP_ATTEST_VERIFY_PERMITS`、`AI_CALL_TIMEOUT_SEC`、`AI_SCAN_DEADLINE_SEC`、`SCAN_PROMPT_VERSION`、`PG_*_MIN_IDLE` |

---

## B. Changelog 缺口 / 过时（逐条：文档说 X，代码做 Y）

### B1. install/attestation action 名全部过时（**blocker**）
- Changelog L11：`m_install_createAttestChallenge / m_install_recoverInstall / m_install_attestExisting`；L10：`m_install_createInstall / m_install_updateInstall`。
- 代码实际（`bff/api/customer/auth/InstallApiController.kt`，`19c4ba85` 改名后）：`m_auth_install_createIosInstall`、`m_auth_install_createAndroidInstall`、`m_auth_install_updateOne`、`m_auth_install_createAttestChallenge`、`m_auth_install_recover`、`m_auth_install_attest`。
- release L48 同样写 `m_install_createAttestChallenge / m_install_recoverInstall / m_install_attestExisting` —— 三处旧名，客户端会照文档实现错误路由。
- Changelog L19 只记录了 create 的平台拆分，未记录 recover/attest/updateOne/createAttestChallenge 的最终名。

### B2. `m_customer_deleteAccount` 名过时（**should-fix**）
- Changelog L16 写 `m_customer_deleteAccount`；实际常量 `REQNAME_DELETE_ACCOUNT = \"m_auth_account_deleteOne\"`（`AuthApiController.kt:59`，`dc086454` 改名）。

### B3. DB 迁移行漏 V16 且停机清单不完整（**blocker**，与 §D 联动）
- Changelog L39「DB 迁移」止于 V15，且「V6/V8 要求停机窗口内先迁移后发代码」。V16（`core_media_upload_record → core_media_file_record`）不在该行；而 v1.0.3 的 `UploadRecord.kt` 是 `@Table(\"core_media_upload_record\")`，迁移后旧实例读/写直接失败 —— **V16 与 V6/V8 同属\"必须停机\"迁移**，文档漏列。
- release L38「V4–V15/16，以发布时实际为准」含糊；L68（feature/install 小节）仍写「先 flywayMigrate（V4–V9）」，与 V10–V16 现实不符。

### B4. wire 加密 bullet 与最终代码语义基本一致，但漏了 env（**should-fix**）
- Changelog L14 正确写了 `x-wirep-version: 2`、400004（明文拒）/400003（解密失败）、5MB 上限，但未列 `WIRE_CRYPTO_KEYS` / `WIRE_MAX_DECOMPRESSED_BYTES`；算法表述「X25519+HKDF+AES-256-GCM」未提\"最终实现是 RFC 9180 HPKE（BouncyCastle），非手写 HKDF\"（`WireCrypto.kt:12-17`）。
- **`WIRE_CRYPTO_KEYS` 缺失时 required 模式下 `/api/**` 全灭（400003/400004）——release L41 的「env 核对」步骤只列 `AUTH_JWT_PRIVATE_KEY` / `APP_ATTEST_GLOBAL_ENABLED` / `APP_ATTEST_CHALLENGE_SECRET`，漏了 `WIRE_CRYPTO_KEYS`（blocker 级运维风险：忘记配置 = 全量接口不可用，且\"大声失败\"是设计）。**

### B5. `GOOGLE_WEBHOOK_TOKEN` / `/webhooks/iap/google` 鉴权收紧（**blocker** 运维面）
- 代码（`WebhookController.kt:110-133`）：token 未配置时端点一律 403（此前端点**完全无鉴权**，公网可写通知表）；token 走 `?token=xxx` 或 `X-Webhook-Token`，恒定时间比较。
- Changelog 与 release 均未提及：新 env `GOOGLE_WEBHOOK_TOKEN`（prod env 清单 + release 步骤 6 都缺）。上线后若不把 token 配进 Pub/Sub push URL，IAP 订阅状态静默停摆。
- 附带：该 token 经 query string 进 `RequestLoggingFilter` 的 `path` 字段（`uri + query`，L84）——即 P1 日志脱敏项的一部分，文档需说明。

### B6. `q_ai_scan_getById` `include` 参数（已记录，一致）
- Changelog L17 与代码（`AiInputs.kt` `FindScanByIdInput.include` + `requireScanInclude`，commit `43168044`）一致。✔

### B7. DR/scan 同步返回的 `errorCode` 字段（**nice-to-have**）
- `m_ai_deepResearch_run` 现在同步返回任务 id + `errorCode`（`TASK_SUBMISSION_FAILED` 免二次查询，commit `37698b3b` 定稿）；`AiTaskErrorCodes`（AI_FAILED/TIMEOUT/TASK_SUBMISSION_FAILED/INTERNAL_ERROR）对客户端可见。Changelog「AI 扫描/DeepResearch 异步化」bullet（L12）未提该出参字段与码表。

### B8. i18n 推送文案（**nice-to-have**）
- 新增 `spring.messages` 绑定 + 17 个 `i18n/messages*.properties`（`2feca2f6`/`50fafb95`）：push 通知文案本地化。Changelog L12 只写了「完成后 FCM push」，未提文案 i18n 机制与 locale 集（ar/bg/da/el/hr/ro/sv 等超出 2026-09-10 条的 10 语言归一集——归一集不变，i18n 资源集更大，两者无冲突但值得注一句）。

### B9. 其他未入 Changelog 的行为变化（**nice-to-have**）
- `SPRING_PROFILES_ACTIVE` 默认兜底 `local`（`application.yml`，原无默认值缺 env 即启动失败）——本地/CI 行为变化，运维需知。
- Redis Lettuce 命令超时 500ms（key 池/限流快速降级放行）——限流\"降级放行\"语义不变但触发更快。
- 请求 body 全局 5MB 上限对**明文**也生效（`WireCryptoFilter` Content-Length 预检，`/api/**` 明文/密文统一 400000）——L14 只说\"超限 400000\"未点明明文也拦。

---

## C. wire 版本一致性（高优先）

**最终代码事实**（以 `core-api/.../infra/http/` 为准）：

| 项 | 代码实际值 | 位置 |
|---|---|---|
| 版本号 | **2**（`WIRE_VERSION_VALUE = \"2\"`、`WireCrypto.VERSION = 2`） | `WireCryptoFilter.kt:152`、`WireCrypto.kt:120` |
| 头名 | `x-wirep-version`（`RequestHeaders.WIREP_VERSION`） | `RequestHeaders.kt:22` |
| Suite | RFC 9180 HPKE base：DHKEM(X25519, HKDF-SHA256) + AES-256-GCM，BouncyCastle `org.bouncycastle.crypto.hpke`，**无手写常数/黑名单** | `WireCrypto.kt:12-17,138` |
| 线格式 | 请求 `ver(1)=2|kid(1)|enc(32)|flags(1)|HPKE-Seal‖tag`，AAD=前 35B；响应 `flags|nonce(12)|AES-GCM‖tag`，resKey=`HPKE-Export(\"ifmix-wire-v2-res\",32)`；gzip 阈值 4096，解压上限 1MB | `WireCrypto.kt:19-30` |
| 400004 | `WIRE_REQUIRED`：required 模式下 `/api/**` 明文或 `x-wirep-version` 版本不符（**原 ts 时效预留码改作此用**） | `ErrorCode.kt:18-20` |
| 400003 | `WIRE_DECRYPT_FAILED`：解密失败/kid 未知/解压超限，**keys 为空时加密请求也 400003**（大声失败） | `ErrorCode.kt:18`、`WireCrypto.kt:146` |
| 明文降级 | **无**：required 模式明文一律 400004；optional 仅 local/dev（`application-local.yml` `mode: optional`） | `WireCryptoFilter.kt:70-87`、`application-prod.yml` |

历史：`2b63f3e9` S1 落地\"wire v3 HPKE + x-wirep-version（35B AAD）\"→ `081e3b62`「强制加密定稿：版本 3→2、无版本协商、required/optional 模式」。版本号从 3 回退为 2，头名保留 `x-wirep-version`。

**文档冲突清单**：

1. **`docs/design/infra/wire-encryption.md` §10 整体过时（blocker）**：§10 定稿为 **v3**（`ver(1)=3`、`info=\"ifmix-wire-v3\"`、`\"ifmix-wire-v3-res\"`、env `APP_WIRE_KEYS`、\"v3 直接取代 v2，明文 v1 降级保留\"、\"无 `x-proto-version: 3` 的请求按 v1 放行\"）。最终代码是 **v2 + `ifmix-wire-v2` + `WIRE_CRYPTO_KEYS` + required 无明文**。**info 字符串与版本号是逐字节协议字段——若客户端仓库按 §10/`wire-v3-plan-*.md` 实现过 `ifmix-wire-v3` 或 ver=3，联调必然全量解密失败**；需与客户端 `wireCrypto.ts`（§10.4 标 ✅ 完成）核对实际落点用的是 2/`ifmix-wire-v2`（`62303a93` 称客户端 b0d000e 已落地 fail-fast/降级删除，推测已对齐，但必须验证）。文件头「状态：v2 已实现但**未发布**；v3 提案已评审定稿」两句话在 2026-10-06 全部失效。
2. **`docs/design/infra/wire-v3-plan-server.md` / `wire-v3-plan-client.md`（should-fix）**：仍按 v3 + `APP_WIRE_KEYS` + 「版本判断从 2 改为 3」写。这两个是 §10.4 的实施计划，最终被 `081e3b62` 的 3→2 定稿改写，但文档未回写。
3. **代码注释过时（should-fix）**：
   - `RequestHeaders.kt:19-20`：「缺省/1 = 明文；**3** = body 加密… 响应头同名回传 **3**」——实际是 2；「旧名 x-proto-version（v2 从未上线）已随 v2 一并移除，**v3 用新名 x-wirep-version**」——历史叙事停在 3 时代。
   - `application.yml:64`：「空 = 不支持 **v3**（v3 请求返回 400003，**客户端自动降级明文**）」——版本号错；且\"降级明文\"在 required 模式下不成立（明文是 400004 被拒，不是放行）。
   - `application-prod.yml` 注释同病：「缺省 = 不支持 v2，v2 请求全部 400003，客户端自动降级明文」——版本说对了，\"自动降级明文\"错（required + 无降级定稿于 `081e3b62`/`62303a93`）。
   - `WireCrypto.kt:33`：向量目录名 `wire-v3` 已注明\"历史遗留\"，OK。
4. Changelog L14 本身**正确**（v2/400004/400003/无协商），是唯一与代码一致的 wire 描述。✔
5. wire-encryption.md §9「已决策：保留降级与 kill switch」与最终 required 无明文降级定稿**矛盾**（§9 是\"v2 时代\"结论，被 `081e3b62` 的定稿推翻但没改）。应加修订注记（nice-to-have，因 §10 头声明\"§9 对 v3 继续有效\"）。

---

## D. 迁移顺序 / 发布计划正确性

逐文件核实（`db/migration/`）：

| 迁移 | 对旧实例的影响 | 计划是否覆盖 |
|---|---|---|
| V6 `install_id` varchar→uuid + core_install PK 收敛 | **旧代码写 varchar installId 即失败** → 必须停机先迁 | ✔（Changelog L39 / release L38、L68） |
| V8 `core_ai_agnes_key`→`core_ai_api_key` | 旧代码查旧表名即失败 → 必须停机先迁 | ✔（同上） |
| **V16** `core_media_upload_record`→`core_media_file_record` | v1.0.3 `UploadRecord` 实体绑旧表名 → **同样必须停机先迁** | **✘ 漏列**（Changelog DB 行止于 V15；release 只写 V6/V8；「V4–V15/16」措辞含糊）。`19c4ba85`（10-06）才新增 V16，两份文档写于其前。 |
| **V12** `ai_scan_record.status` 语义改写（旧 20=完成/30=失败 → 新 20=IN_PROGRESS/30=SUCCESS/40=FAILED，含存量行 UPDATE） | 旧代码写 20/30 会被新代码误读为 IN_PROGRESS/FAILED——混合写期与**回滚**场景危险 | ✘ 未在\"必须先停机\"清单中（停机+先迁的发布顺序下可接受，但应补一句 V12 回滚窗口风险） |
| V4/V5/V7/V9/V10/V11/V13/V14/V15 | 加列/加表/加索引（V5 含 UNIQUE 索引、V11 加 jsonb 列…），旧代码可写 | 无停机要求，✔ |

- release L68「先 flywayMigrate（V4–V9）」是 feature/install 小节的历史文案，在合并后的 v1.0.6 计划中仍是误导（实际 V4–V16）。**should-fix。**
- core-job attest 三个 job「决定不调度」：代码事实 ✔（`AttestJobs.kt:25-78` 三个 `@Bean` 整体注释，`--job.name=attest*` 退出码 2 拒绝；cleaner 保留可单测）；release 决策 3 + L52 已如实记录 ✔。**`release.md` 一致，Changelog 未提 core-job（其口径\"记录 core-api 面向客户端/DB 的变更\"可接受，但 V15 表是 job 依赖的——建议加一句说明）。**
- `:core-job:test` 在 release 步骤 1 的全量测试命令中 ✔。

---

## E. P1 修复项现状（release L25-30「2026-10-06 复核：仍未修」逐条核实）

| P1 | 复核结果 |
|---|---|
| **WireCrypto 低阶点黑名单** | **已失效/obsolete**：代码里根本不存在黑名单（`grep 低阶/LOW_ORDER` 仅命中测试注释）。S1 起改用 BouncyCastle RFC 9180 HPKE，低阶点由 HPKE 库在 DHKEM 验证时拒绝（`WireCryptoTest.kt:169`「全零 X25519 低阶点：HPKE 库拒绝」）。「黑名单常数错误」针对的是已不存在的旧手写实现。→ release L26 的「仍未修」是**错误结论**，应改为 obsolete（连同 Changelog L32 的「未修（代码无低阶点校验）」一并修订）。小瑕疵：`WireCryptoTest.kt:170` 该用例 payload 首字节写的是 `3`（旧 v3 版本号残留），实际先触发 version 校验而非低阶点路径——nice-to-have。 |
| **ClientIpResolver 可信 IP** | **仍 open**：`ClientIpResolver.kt` 仍 `X-Forwarded-For` 取第一段，无 `CF-Connecting-IP`/可信代理链；所有 `:ip:` 限流 key 可伪造绕过。与文档一致。 |
| **日志脱敏 refreshToken/authCode** | **仍 open**：RPC 后 refresh 凭证在**加密 body 的 input**（`AuthApiController.kt:94-100` `RefreshInput.refreshToken`），wire 过滤后是明文；`RequestLoggingFilter.kt:88` 4xx/5xx WARN 原样打 `req`（截 2000）→ refreshToken 明文进日志。`logout` 同（L113）。Google webhook token 经 `?token=` 进 `path`（L84）。与文档一致。 |
| **AI 惰性超时 300 vs 600** | **仍 open**：`ScanAggHandler.kt:425` `STALE_IN_PROGRESS_SEC = 300L`，`application.yml` `AI_SCAN_DEADLINE_SEC:600`。300–600s 的在途任务会被查询侧误判 TIMEOUT。与文档一致。 |
| **DR 空 premium_result 判失败** | **仍 open**：`DeepResearchTaskService.kt:81` 注释「AI 正常返回即任务 SUCCESS；scan_status 原样保存在 basicResult」，无 `premium_result` 非空校验；release 决策 6（「缺 premium_result 算失败」）未落地。与文档一致。 |

**P2 抽查**：
- RateLimiter INCR/EXPIRE 原子化：**仍 open**（`RateLimiter.kt:72-86` `increment` 与 `expire` 分离）。
- webhook 幂等唯一索引：**仍 open**（`pay_store_notification` 只有非 unique 的 `store_notif_platform_token_idx`（V1 baseline:775），去重靠应用层 `existsByPlatformAndToken`（`PaymentWebhookHandler.kt:41`），存在竞态）。
- FirebaseAppRegistry 负缓存：**已修**（`FirebaseAppRegistry.kt` null 哨兵 `Optional` 缓存，注释明示\"缓存无配置结论\"）。
- AiChatClientFactory 无淘汰缓存：**仍 open**（`AiChatClientFactory.kt:33` 裸 `ConcurrentHashMap`）。
- scan 表 customer_id 索引：**仍 open**（`ai_scan_record` 仅 `scan_record_project_id_idx`）。
- bind 关系唯一索引：V5 建了 `core_install_customer_rel_uq`（全量 unique，install_id+customer_id）——与 P2 所指是否同一项待确认（若 P2 要的是带 deleted_at 的 partial 索引则仍 open）。

**结论：release 的\"仍未修\"复核中，4/5 准确；WireCrypto 低阶点项应改判 obsolete（should-fix，避免误导后续安全声明）。**

---

## F. design 文档矛盾（Konvert 为主线）

**代码最终事实**（2026-10-06）：
- `core-api/build.gradle.kts:66-67`：Konvert 4.5.1 KSP 已长期挂上。
- demo 出参：`dto/demo/DemoKonvertMappers.kt`（`@Konverter`，KSP 生成 `DemoKonvertMappersImpl`，多参聚合按\"constant 占位 + 调用方 copy\"回退，`DemoQueryService.assemble()`）。
- ai 出参：`dto/ai/AiKonvertMappers.kt`（K3，`basicResult`/`latestDeepResearch` constant null 规避稀疏实体）。
- auth 出参：`dto/auth/AuthKonvertMappers.kt`（K4；MeRes 组装保留手写）。
- cs 入参：`@KonvertTo`（K0 试点）；**media、pay 出参保留手写**（`PayController.kt:55` `expiresAt` 带条件转换；media presign 手写 DTO）——与 `konvert-rollout-server.md` §5.3 判定一致。

**文档矛盾**：
1. **`docs/design/api-protocol/rpc-protocol-decisions.md` L54（现行文档）：「默认不引入 Konvert 做实体映射」——与最终代码相反（Konvert 用于 demo/ai/auth 实体→Res 映射）**（should-fix）。§5「三层分工」的第 2 层\"聚合/协议→手写 companion factory\"部分仍成立（MeRes、pay），但\"纯投影→Jimmer DTO 生成，零手写\"被 Konvert 生成替代。该文件被 archive/README 标为\"现行协议与编码规则\"，需加 Konvert 例外条款或指向 `konvert-rollout-server.md`。
2. **`docs/design/api-protocol/archive/rpc-rollout-server.md` §3.2「mapper 规则：弃 Konvert」与 archive/README.md 第 5 行「`konvert-rollout-server.md`（K3/K4 待实施）」——后者已被 `9d5df091`/`744b5889`/`c254ed33` 完成并打回\"待实施\"，README 未更新**（nice-to-have，archive 内）。
3. 叙事链条：`ee772d03`（文档定稿\"弃 Konvert\"，三层分工）→ `b2038bdb`/`11e5debf`/`9d5df091`/`744b5889`（实际逐模块改回 Konvert 生成，`konvert-rollout-server.md` 全阶段完成回写）→ 现行 `rpc-protocol-decisions.md` 仍停在\"弃 Konvert\"。git log 同时存在两套定稿，**最终真值 = Konvert 用于纯投影/出参（demo/ai/auth/cs 入参），手写保留在聚合与含逻辑分支（pay、MeRes、attest 判定）**；文档未收口（should-fix）。
4. wire 系列文档见 §C1/C2（`wire-encryption.md` §10、`wire-v3-plan-{server,client}.md` 停在 v3 叙事）。

---

## G. 建议补进 Changelog / release 的条目（copy-paste 就绪）

**Changelog → Added 区（v1.0.6 段）：**

- **install/attest action 最终名修正（2026-10-06，`19c4ba85`）**：`m_auth_install_createIosInstall` / `m_auth_install_createAndroidInstall` / `m_auth_install_updateOne` / `m_auth_install_createAttestChallenge` / `m_auth_install_recover` / `m_auth_install_attest`（本表 L10/L11 的 `m_install_*` 与 `m_install_recoverInstall`/`m_install_attestExisting` 均作废）；`m_customer_deleteAccount` → `m_auth_account_deleteOne`。
- **新 env（本节遗漏）**：`WIRE_CRYPTO_KEYS`（`kid:base64` 多 kid；缺省 = 不支持加密，required 模式下 `/api/**` 全 400003/400004，启动即大声失败）、`WIRE_MAX_REQUEST_BYTES`（默认 5242880）、`WIRE_MAX_DECOMPRESSED_BYTES`（默认 1048576）、`GOOGLE_WEBHOOK_TOKEN`（缺省 `/webhooks/iap/google` 一律 403，IAP 通知整体关闭——**上线前必须配置并同步 Pub/Sub push URL**）、`AI_CALL_TIMEOUT_SEC`（360）、`AI_SCAN_DEADLINE_SEC`（600）、`APP_ATTEST_VERIFY_PERMITS`（32）。
- **Google IAP webhook 鉴权收紧（breaking，运维）**：`/webhooks/iap/google` 需共享 token（`?token=` 或 `X-Webhook-Token`，恒定时间比较）；未配置 = 端点关闭（403）。旧端点此前无鉴权。
- **wire 最终版定稿说明**：线协议版本 = **2**（`x-wirep-version: 2`，RFC 9180 HPKE，BouncyCastle 标准实现；历史 3 草案经「3→2 定稿」commit 合并为 2，无版本协商）；400004 = 明文/版本不符（`/api/**` 无明文降级），400003 = 解密失败（含 keys 未配）；ts 偏差仅 warn。
- **`m_ai_deepResearch_run` 同步返回 `errorCode`（`TASK_SUBMISSION_FAILED` 等，`AiTaskErrorCodes` 码表）**：提交失败可同步感知，无需二次轮询。
- **push 通知文案 i18n**：`spring.messages`（`i18n/messages*.properties`，17 locale，缺省回退英文）。
- **启动兜底**：`SPRING_PROFILES_ACTIVE` 缺省回落 `local`（原缺省启动失败）；Redis 命令超时 500ms（限流/key 池快速降级）。
- ~~Fixed 区~~：「WireCrypto 低阶点黑名单」改判 **obsolete**（实现已换 RFC 9180 HPKE，低阶点由 BouncyCastle 库拒绝，无手写黑名单可修）。

**Changelog → DB 迁移行修订：**

- V4→V5/V6→V7→V8→V9→V10–V13→V14→V15→**V16（media 表 RENAME）**；**\"必须停机先迁\"清单 = V6 / V8 / V16**（旧代码引用旧表名/旧列类型即失败）；V12 另含 `ai_scan_record.status` 语义改写（20/30 换位 + 存量回写），**发布后回滚窗口内勿回退旧代码**。

**release → 修订：**

- 步骤 3：「flywayMigrate（V4–V16）」；「必须先停机再迁移」处补 **V16**（并注 V12 状态语义改写的回滚风险）。
- feature/install 小节「V4–V9」改为「V4–V16（本小节仅 install 域 V4–V6 + 台账 V9）」。
- 步骤 6 env 核对补：`WIRE_CRYPTO_KEYS`（required 模式强依赖）、`GOOGLE_WEBHOOK_TOKEN`（IAP 通知开关）。
- P1 清单：WireCrypto 低阶点项改判 obsolete（含一句\"测试 `WireCryptoTest` 低阶点用例依赖 HPKE 库拒绝，并修掉用例中残留的 v3 版本字节\"）。
- §C 文档核对项（发布前人工 1 条）：**客户端 `wireCrypto.ts` 必须使用版本号 2 + `ifmix-wire-v2` / `ifmix-wire-v2-res` 上下文**（若按 `wire-encryption.md §10` 的 v3 文案实现过则全量解密失败；`62303a93` 记录客户端 b0d000e 已对齐 fail-fast，需最终确认）。

**design 文档修订（should-fix 一批）：**

- `wire-encryption.md`：头部状态改为「v2（原 v3 草案，3→2 定稿）已实现；required 无明文降级」；§10 全文加\"已被 3→2 定稿改写\"注记（版本号、info 串、env 名三处）；§9 降级结论加修订注记。
- `wire-v3-plan-server.md` / `wire-v3-plan-client.md`：加「v3 定稿为 v2 + WIRE_CRYPTO_KEYS」状态头。
- `rpc-protocol-decisions.md` §5：补「Konvert 例外条款：纯投影出参由 Konvert 生成（demo/ai/auth，见 konvert-rollout-server.md K2–K4 完成记录），聚合/含逻辑分支保留手写」。
- `api-protocol/archive/README.md`：`konvert-rollout-server.md` 状态改为「已实施（K2 11e5debf / K3 9d5df091 / K4 744b5889）」。
- 代码注释修订：`RequestHeaders.kt:19` 版本「3」→「2」；`application.yml:64` 与 `application-prod.yml` 的\"客户端自动降级明文\"→\"required 模式无明文降级（400004）\"。

---

## 重要发现 Top 15（回给调用方）

1. **[blocker]** install/attest 六个 action 在 Changelog L10/L11 与 release L48 全部过时（`m_install_*`→`m_auth_install_*`，`recoverInstall`→`recover`、`attestExisting`→`attest`），`m_customer_deleteAccount`→`m_auth_account_deleteOne`（`19c4ba85`/`dc086454` 后未回写）。
2. **[blocker]** V16（media 表 RENAME，`19c4ba85` 新增）不在「必须停机先迁移」清单（现为 V6/V8）与 Changelog DB 迁移行（止于 V15）；旧实例迁移后读旧表名即失败。
3. **[blocker]** `WIRE_CRYPTO_KEYS` 未进 release 步骤 6 env 核对；required 模式下缺配 = `/api/**` 全灭，而这是设计上的\"大声失败\"。
4. **[blocker]** `GOOGLE_WEBHOOK_TOKEN` 缺失时 IAP webhook 一律 403（`WebhookController.kt:110-122`，端点此前**完全无鉴权**）——Changelog/release 均未提，上线漏配 = IAP 静默停摆。
5. **[blocker]** `wire-encryption.md §10` + `wire-v3-plan-{server,client}.md` 仍定稿在 **v3**（`ifmix-wire-v3` info、`APP_WIRE_KEYS`、ver=3），最终代码是 **v2 + `WIRE_CRYPTO_KEYS` + `ifmix-wire-v2`**；客户端若按 v3 文案实现则全量解密失败，需人工核对客户端实际实现（`62303a93` 称已对齐，未验证）。
6. **[should-fix]** `RequestHeaders.kt:19` 注释仍写\"3 = body 加密、回传 3\"；`application.yml`/`application-prod.yml` 注释写\"客户端自动降级明文\"——与 required 无明文定稿（`081e3b62`）矛盾。
7. **[should-fix]** release P1 复核「2026-10-06 仍未修」中 **WireCrypto 低阶点项已 obsolete**（手写黑名单不存在了，BouncyCastle HPKE 库拒绝低阶点）；另 4 项（ClientIpResolver 仍取 XFF 首段、RequestLoggingFilter WARN 打明文 refreshToken、STALE=300<600、DR 空 premium_result 判成功）核实**确实仍未修**。
8. **[should-fix]** release L68「V4–V9」与步骤 3「V4–V15/16」两处迁移范围表述与实际 V4–V16 不符。
9. **[should-fix]** V12 改写 `ai_scan_record.status` 语义（20/30 换位 + 存量 UPDATE），回滚窗口风险未在发布计划中提及。
10. **[should-fix]** `rpc-protocol-decisions.md` L54「默认不引入 Konvert」与最终代码（demo/ai/auth 出参 Konvert 生成，`11e5debf`/`9d5df091`/`744b5889`）矛盾；git log 中「弃 Konvert」（`ee772d03`）与「换 Konvert」（K2–K4）两套定稿并存，未收口。
11. **[nice-to-have]** `api-protocol/archive/README.md` 仍写 konvert-rollout-server「K3/K4 待实施」，实际已完成（`c254ed33` 回写）。
12. **[nice-to-have]** `WireCryptoTest.kt:170` 低阶点用例 payload 首字节残留 `3`（v3 时代），实际先被 version 校验拦下，未真正验证低阶点路径。
13. **[nice-to-have]** Changelog 未记：`m_ai_deepResearch_run` 同步 `errorCode` 出参（`AiTaskErrorCodes` 码表）、i18n push 文案 17 locale、`SPRING_PROFILES_ACTIVE` 默认 local 兜底、Redis 500ms 命令超时。
14. **[已核实一致 ✔]** core-job 三个 attest job 入口整体注释（不调度）与 release 决策 3/L52 一致；Changelog wire bullet（v2/400003/400004/无协商/5MB）与代码一致；`q_ai_scan_getById` include 参数（`43168044`）与 Changelog L17 一致。
15. **[nice-to-have]** P2 抽查：RateLimiter INCR/EXPIRE 分离、webhook 无唯一索引、AiChatClientFactory 无淘汰缓存、scan 表无 customer_id 索引——仍 open；FirebaseAppRegistry 负缓存**已修**。

意见:
   A.B. 按照代码更新文档 C. 是 2, 不降级. 更新文档. D. app 还没发布 不考虑兼容性.  E.1改 2 改 3.改 4.改 5.改 RateLimiter改 F.Konvert
  我在另一个 session 准备换成 jimmer dto, 先忽略. G.更新文档.
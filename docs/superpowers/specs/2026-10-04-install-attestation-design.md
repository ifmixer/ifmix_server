# createInstall 平台证明（App Attest / Play Integrity）— 设计规格 v4

日期：2026-10-04
状态：待审查（v4）
范围：服务端 `ifmix_server/core-api` + `core-job`，客户端 `antique/apps/antique` + `antique/apps/shared`

> **实施范围（2026-10-04 决定）**：目前只有 iOS 客户端。**一期 1a 只实现 iOS**（App Attest + recover）。Android（Play Integrity）协议保留在本文作为 1b，Android 客户端发布前实现。Web / Turnstile 暂不考虑。

---

## 0. 修订记录

### v4（第四轮 review）

| review 项 | 处理 |
|---|---|
| [阻断] replay_or_reused 无持久状态，持续重发 create | §6.4 新增 `RECOVER_PENDING`。转移以内存状态为准，并同步写入存储；写失败最多导致重启后多发一次 create（理由见 §6.4 规则 2）。原生副作用调用之前的写入仍然必须成功 |
| [高] Google 429 可被诱发，fail-open 可被利用 | 删除 `failOpenOnUnavailable`：ENFORCE 下 Google UNAVAILABLE 一律 503002；大面积故障由运维切 mode / kill switch（§4.3）。Redis 部分后来按用户决定改为放行（见下面「用户决定」一行） |
| [高] 503 重试耗尽合法用户日额度 | 已被后续设计取代：日窗口挪到证明校验之后，503002 不消耗日额度，不再需要退款（§4.6） |
| [中] ENFORCE 配置不完整自动降级 OBSERVE | 改为 fail-closed：返回 503002，`attest.config_invalid` 日志节流后告警；不把整体 health 置 DOWN（理由见 §4.1） |
| [中] BLOCKED 对已签发 installToken 无效 | 一期语义改为「禁止 recover / 新绑定」；真正封禁的升级路径列入二期（§5.4、§8） |
| [低] proofStatus 组合约束 | §5.1 写明校验规则 |
| [低] ATTESTED_PENDING 写失败的孤儿 key | §6.4 打 `pending_state_persist_failed`；fraud metric 分析剔除（§8） |
| （用户决定）Redis 故障放行，可用性优先 | 限流：放行，打 `ratelimit.degraded`（§4.6）。attest：challenge 改为 HMAC 签名的无状态格式，签发和校验不依赖 Redis；一次性消费和 Android 去重在 Redis 故障时跳过，放行并打 `attest.redis_degraded`（§3.1、§4.3）。ERROR 日志都做了节流 |
| （用户决定）install 来源商店 | 新增 `storeType`：10=APP_STORE / 20=GOOGLE_PLAY，客户端上报，write-once，仅用于统计（§5.9） |
| （用户决定）限流 | 两层：IP 层做系统防护（阈值大），install 层防滥用（阈值小），任一层超限即拒绝。createInstall：入口 100/60s/IP；未验证 100/天/IP；VALID 1000/天/IP。createAnonymous：5/install/天 + 100/60s + 1000/天/IP。scan 与 DeepResearch：5/min + 100/install/天 + 100/min + 1000/天/IP。install 层只认 `tokenInstallId`，不区分是否已验证，不需要 att claim。每个 action 显式配置，重启生效（§4.6） |
| （review）真机 fixture 方案 | 严格限定 dev 使用；遵守 key 生命周期；使用生产协议；导出版本化 JSON 并用 expo-sharing 传输；JUnit 固定 Apple 根证书 + development 模式；带 Tag，不进默认 CI（§10.1） |
| （review）att 信任链 | 认可了该模型（只认 `tokenInstallId`；claim 经 VerifiedToken → Actor → ActionContext 传递；`signAccess` 加不变量），但**整套 att 下游放宽已整体移出本期**（模型说明放在 §8「以后」），本期不保留任何代码入口 |
| （review）限流结构：双层「per-install + IP 聚合」、VALID 高位上限、不放宽旧客户端、去掉"立即生效" | **VALID 高位上限：采纳**（1000/IP/天）。**不放宽旧客户端：后被用户决定覆盖**（下游 IP 阈值按产品决定上调，不是兼容需要；见上面的「用户决定」行）。**"立即生效"：采纳**，改为重启生效。**双层 per-install + IP：采纳**（用户决定）。所有下游请求按 `tokenInstallId` 做 install 层限流，再叠加 IP 层；install 层不区分是否已验证（§4.6） |
| （review）状态机与 rollout 闭环 | 采纳：`CreateInstallResult.attestationStatus`（10/20/30），只有 10 能进 REGISTERED；challenge 返回 `enabled`，服务端 OFF 时客户端不生成 key；新增 UNSUPPORTED 终态；429 分成 429000（验签前）和 429002（验签后，新增），客户端分别处理；attest / assertion 加超时和 attemptId，迟到结果丢弃；challenge 改为日窗口通过后再消费；`sign_count NOT NULL DEFAULT 0`；`AppAttestTrustAnchors`（固定 Apple 根证书指纹）；验签加进程内信号量；补充 Redis 全故障的影响范围；expiresInSec 改为 270；清理残留表述。**存量 install：用户选择补证（方案 A），新增 `m_install_attestExisting`（§6.7）** |
| （review）前端实现对照 | 采纳：§6.6 更正，createInstall 的 operation 文本一定会变，补充了发布顺序和契约测试；`attestReady` 改为惰性、幂等的 `initAttestation(ctx)`，`_layout` 按 loadEnvOverride → initAttestation → ensureInstallWhenOnline 串行执行；客户端日志脱敏（同时修复了 installToken 本来就没有脱敏的问题）；`next()` 改为传入 `InstallSnapshot`，凭证丢失但状态是 REGISTERED 时走 recover，所有清除路径都收口到协调器；发布门槛：capability 和 profile、codesign 检查 entitlement、TestFlight 上用 production 跑通 create 和 recover、平台发布清单（声明了 Android 就必须先过这一项才能切 ENFORCE）（§4.5、§6.3、§6.6、§6.8、§9） |
| （review）环境切换 / mutex 边界 | 采纳：切换 API 环境时直接清掉 SecureStore 里的 install 和 session（ponytail：开发场景可以接受，按环境分开存列为升级路径）；硬规则：mutex 从不跨外部 IO 持有，用「短临界区 + epoch / attemptId 的 CAS 提交」，404 恢复中的创建和 refresh 都在锁外；`wipeLocalUserData` 改为「精确 + 前缀」两种保留规则；fixture 改用 `pauseAndDrain` + finally 恢复；`next()` 显式传入 purpose 和 targetInstallId；补充真实存储 key、404 集成和并发、不死锁这几类测试（§6.8、§7） |
| （review）客户端生命周期协调 | 采纳：attestState 按 `projectId.apiEnv` 分开存，并加信封（schemaVersion / appAttestEnvironment / appVersion），不匹配时不发送 proof；UNSUPPORTED 在 App 升级后重新检测；新增 install 协调器（mutex + installEpoch + `clearInstallIfCurrent`），create / backfill / 前台清除共用；**更正：refresh 实际上会 bind**（`AuthAggHandler.refresh`，上一版依据的是过时文档，判断错误），所以有 session 时 404001 自动收敛：重建 install，再用新 installToken refresh 重新绑定；补充生命周期表（删除账号时保留 attestState，Dev 全新设备时清除）；损坏状态保守处理；统一调度规则；前台错误分类；fixture 与生产隔离（§6.7、§6.8） |
| （review）补证并发 / 额度 / 404 收敛 | 采纳：事务内先 `SELECT … FOR UPDATE` 锁住 install 行，再重查绑定（以事务内结果为准，幂等路径也一样）→ 退役最早的 key（created_at, id）→ 插入；每天 3 把的额度只在确认是新 key 时才检查；404 拆成精确的 404001 INSTALL_NOT_FOUND（installToken 永不过期，不会自然变成 401）：没有 customer session 时清掉 install 重建，有 customer session 时的处理已被下一行更正为自动收敛；REGISTERED 和 pending 状态记录 installId / targetInstallId；403001 replay 最多重发 1 次，之后转 recover，recover 拿回来的 installId 不一致时当作冲突；更新了错误码表（§4.4、§6.4、§6.7、§7） |
| （review）补证闭环 | 采纳：先查 key 绑定再消费 challenge，同 install 的重发幂等返回 10；`AttestGuard` 拆成 `verifyProof`（纯验证）、`decideCreateInstall`（套 mode）、`consume`，attestExisting 的 INVALID 一律返回 20；新增 409001（key 绑在别的 install 上，客户端废弃 key，**不 recover**）和 403002（BLOCKED / RETIRED）；鉴权严格只认 installToken，客户端用 `makeInstallGqlOpts`；每个 install 最多 5 把 ACTIVE key，超出后轮换为 RETIRED；404 时停止补证、不动会话；status=30 的处理；明确补证只是迁移能力，存量 token 的收口放到二期（§6.7、§8） |
| （review）下游限流的执行顺序、legacy 和隔离 | 采纳：改为 install 层 → IP 层（IP 层拒绝时 install 额度不退，属于明确的语义；Lua 原子预占列为升级路径）；没有 iid 的 legacy 请求沿用旧的严格阈值，并用单独的计数器，关闭 fallback 后直接拒绝；所有 key 按 projectId 隔离，本期不设全站总闸；`RateLimiter` 统一为 `check(window, key, limit): RateLimitResult`，短窗口和日窗口共用降级日志控制器（§4.6、§7） |
| （review）日窗口语义 | 采纳：日窗口统计的是「当天允许进入创建事务的尝试数」；事务阶段失败（key_reused / 唯一冲突 / DB 错误）不退额度；两个计数器独立（OBSERVE 下单 IP 最多 50+100），伪代码与 12 项测试补齐（§4.6、§7） |
| （review）fixture 修正 | fixture 不含任何 secret；codec 用固定 Clock 测；重放负例模拟 counter 持久化；SYSTEM_FAILURE / UNKNOWN 只重试 1 次后丢弃 key；安装命令改为 pnpm workspace 写法（§6.4、§7、§10）：**采纳** |
| （v4 复审）§4.6 现状不准 / DECR 退款不安全 / generateKey 规则字面不可行 / config_invalid 日志洪泛 | §4.6 写明日窗口在 `feature/wire-encrypt` 还没合入，改用按 UTC 日分桶的 key（退款方案后来被删除，见上）；§6.4 规则 1 把 generateKeyAsync 列为例外；§4.1 日志按 projectId+configHash 节流 |

### v3（第三轮 review）

| review 项 | 处理 |
|---|---|
| [阻断] 同 key 二次 attest | §6.4 状态机重做：attest 前先持久化 ATTESTING 意图，attest 后持久化 ATTESTED_PENDING（含 proof）；有效期内重发同一 proof；已 attest 的 key 只走 assertion；recover 404 → 废弃 key |
| [高] INVALID proof 选弱平台绕过 | 取消 per-platform mode，改为**单一 project mode**；未 VALID 前不信任 provider / header（§4） |
| [高] OBSERVE 下 key_reused 建未绑定 install | key_reused 与 mode 无关，一律 403001 → recover（§5.3） |
| [高] proof 临时失败被降成 missing 403 | `ProofStep` 显式区分 `unavailable`；客户端带 `proofStatus=UNAVAILABLE` 发请求，服务端 OBSERVE 放行 / ENFORCE 返回 503002（§4.3、§6.2）——**偏离 review 建议，理由见 §4.3** |
| [中] Guard 职责矛盾 / recover 未校验 ACTIVE | §5.2 统一表述；乐观更新加 `status = ACTIVE` |
| [中] entitlement 环境值 | §6.1 按 `APP_ENV` 映射；服务端 env 与之对齐 |
| [低] 保留无期限 | §5.7 evidence 90 天清理任务 |
| 状态存储位置 | iOS attest 状态存 AsyncStorage 而非 SecureStore——**偏离 review 建议，理由见 §6.4** |

### v2（第二轮 review）

clientDataHash 字节契约；durable 表只存 VALID；403001 / 503002 拆分；绑定决策移入 Install 事务；provider 110/120；Android requestHash 绑定上下文；challenge 验证后原子消费；fraud metric 按 `next_refresh_at`；core-job 独立依赖；输入上限 / 固定 signals；指标改结构化日志 + SQL。

---

## 1. 目标与非目标

### 目标

- `m_install_createInstall` 可要求一次新鲜的、来自真机正版 App 的平台证明：iOS App Attest，Android Play Integrity Standard request。
- 服务端自建验证，保存并利用原始平台信号（iOS keyId / 公钥 / receipt / fraud metric；Android 原始 verdict）。
- iOS：install 绑定 Secure Enclave key；响应丢失或本地凭证丢失时用 assertion 找回，不重复建 install。
- 单一 createInstall API，按 provider 区分平台证明；以后新平台新增 provider 即可接入。
- 全链路「默认关、显式开、先观察后强制」。

### 非目标（本期）

- 不保证防重复：一台真机可反复生成新 key / 新 token（防重放 ≠ 设备额度）。
- 不做准入风控策略（只采集信号，见 §8 二期）。
- 不做后续业务请求的 assertion（本期 assertion 只用于 recover）。
- Android 不做响应丢失找回（无长期 key，接受重复 install）。
- 不支持按平台分别设置 mode（见 §4.3）。mode 是 project 级的，作用于所有**已配置**的 provider；未配置的 provider 一律拒绝。
- createAnonymous 不单独证明：它必须带 installToken，而 installToken 只能经 createInstall / recoverInstall 获得。

---

## 2. 决策记录：自建而非 Firebase App Check

| 维度 | Firebase App Check | 自建 |
|---|---|---|
| 服务端可得信息 | 仅 token 有效性 + appId / 时间 | iOS keyId / 公钥 / counter / receipt → fraud metric；Android 原始 app / device / licensing / recentDeviceActivity / Device Recall / App Access Risk |
| Java 服务端 | 无 Admin SDK（`firebase-admin:9.7.0` 零 appcheck 类，已核实） | iOS：WebAuthn4J；Android：Google API |
| 可演进性 | 无 key 身份 | key 绑定 install、assertion 找回、按 key 禁止找回 / 新绑定（真正的 install 封禁见 §8 二期） |
| 成本 | 低 | 中高 |

自建的价值仅在于使用原始信号：一期必须入库并可统计，二期必须进入策略，否则应退回 Firebase 或仅 IP 限流。

---

## 3. 协议

### 3.1 iOS 创建

**challenge 格式（无状态，HMAC 签名，签发和校验都不依赖 Redis）**

```
payload      = ver(1)=1 ‖ issuedAtSec(8, BE) ‖ random(16)
mac          = HMAC-SHA256(challengeSecret, "ifmix-attest-ch-v1\n" ‖ projectId ‖ "\n" ‖ payload)
challengeStr = base64urlNoPadding(payload ‖ mac)                                        // 57 字节 → 76 字符 ASCII
```

- `challengeSecret` 来自 env `APP_ATTEST_CHALLENGE_SECRET`（32 字节 base64）。MAC 绑定 projectId，所以一个 project 签发的 challenge 不能拿到别的 project 用。
- 校验：base64 解码 → 长度和 ver 正确 → 恒定时间比较 MAC → `now - issuedAt ∈ [-30s, 300s]`（-30s 是给多实例之间的时钟差留的余量）。任一项不通过 → INVALID(challenge_invalid / challenge_expired)。
- 更换 secret：环境变量可以同时配置当前值和上一个值（`current,previous`），校验时依次尝试。签发只用当前值。
- 缺少 secret 而全局开关是开着的 → 配置无效（§4.1）。

**字节契约（逐字一致）**

```
客户端：    attestKeyAsync(keyId, challengeStr)                             // 原样传，不预哈希
Expo 内部： clientDataHash = SHA256(UTF8(challengeStr))                     // 已核实源码
服务端验证：clientDataHash = SHA256(UTF8(challengeStr))                     // 对 76 字符的 ASCII 串做 hash，不先 base64 解码
            expectedNonce  = SHA256(authData ‖ clientDataHash)
```

**流程**

```
1. m_install_createAttestChallenge → { enabled, challenge, expiresInSec: 270 }   // 纯计算，不碰 Redis；服务端接受 300s
2. 客户端按 §6.4 取得 proof（每把 key 只 attest 一次）
3. m_install_createInstall(input.proof = { provider: 110, appAttest: { keyId, attestationObject, challenge } })
4. 服务端（事务外，AttestGuard）：
   a. 校验 challenge 的签名和时效（见上）
   b. WebAuthn4J：x5c 链 → Apple App Attestation Root CA；扩展 1.2.840.113635.100.8.2 == expectedNonce；
      SHA256(凭证公钥) == keyId == credentialId；rpIdHash == SHA256(UTF8("{teamId}.{bundleId}"))；
      signCount == 0；aaguid 与 `ios.env` 一致
   c. 一次性消费（AttestGuard.consume，在 §4.6 的日窗口之后执行）：Redis SET attest:used:{SHA256(challengeStr)} 1 NX EX 360
      已存在 → INVALID(replay)
      Redis 异常 → 跳过消费，按 VALID 继续；打 ERROR `event=attest.redis_degraded`（节流同 §4.6）
5. 事务内绑定（§5.3）
```

- 格式错误或解析失败时不消费 challenge。
- **同一份 proof 重发**：如果第一个请求在消费之前就失败了，重发可以成功；如果第一个请求已经消费了 challenge，重发会得到 INVALID(replay)，客户端转去 recover（§6.4）。
- **Redis 整体不可用时的完整影响范围**（可用性优先的明确取舍）：同时失去 challenge 一次性消费、Android token 去重、createInstall 的分钟和日限流，以及 createAnonymous / AI 的限流。OBSERVE 下未验证的 createInstall 等于完全没有频控；ENFORCE 下仍然要求有效的平台证明，但 VALID 的新 key 没有 IP 安全阀。补强措施：`AttestGuard` 的验签用**进程内信号量**限制并发（初值 32，可配置）。拿不到许可时按服务端 UNAVAILABLE 处理（OBSERVE 放行、ENFORCE 返回 503002），防止 Redis 故障期间验签把进程打满。Redis 故障会打 `ratelimit.degraded` / `attest.redis_degraded` 两类 ERROR 日志，应接入告警。
- **同一份 attestation 被重放**：同一份 attestation 在 challenge 有效期（5 分钟）内可以被重放。但被重放的是同一个 keyId，`(project_id, provider, subject)` 唯一约束会把它判成 key_reused，返回 403001，**不会因此多出 install**。实际敞口只有"攻击者拿到一份还没提交的 proof，并抢先提交"这一种情况，而这本来就是持有 proof 的人才能做的事。

### 3.2 Android 创建

**字节契约**

```
nonceStr    = base64urlNoPadding(random 32 bytes)
context     = "ifmix-install-attest-v1\n" + projectId + "\n" + "CREATE_INSTALL\n" + nonceStr
requestHash = base64urlNoPadding(SHA256(UTF8(context)))           // Expo 原样设为 StandardIntegrityTokenRequest.requestHash
```

**流程**

```
1. 客户端：requestIntegrityCheckAsync(requestHash) → integrityToken
2. m_install_createInstall(input.proof = { provider: 120, playIntegrity: { integrityToken, nonce: nonceStr } })
3. 服务端（事务外）：
   a. decodeIntegrityToken：网络错误 / 5xx / 429 → UNAVAILABLE；4xx → INVALID
   b. 校验，不满足 → INVALID(reason)：
      requestPackageName == packageName；requestHash == 服务端重算值；
      |now - timestampMillis| ≤ 5min（Google 可信时间）；appRecognitionVerdict == PLAY_RECOGNIZED；
      certificateSha256Digest ∩ 配置摘要 ≠ ∅；deviceRecognitionVerdict ∋ MEETS_DEVICE_INTEGRITY
   c. Redis SET NX attest:pi:{SHA256(token)} EX 600：已存在 → INVALID(replay)；Redis 异常 → 跳过去重，按 VALID 继续，打 ERROR `attest.redis_degraded`（节流）
   d. licensing / recentDeviceActivity / deviceRecall / appAccessRisk / playProtect：一期只记录
4. 事务内写入（§5.3）
```

`LICENSED` 一期不作硬条件。

### 3.3 iOS 找回（recoverInstall）

```
clientData = "ifmix-install-recover-v1\n" + projectId + "\n" + challengeStr
客户端：   generateAssertionAsync(keyId, clientData)          // 不预哈希；需 fixture 验证 Expo 内部处理
1. createAttestChallenge → challengeStr
2. m_install_recoverInstall({ keyId, assertion, challenge })
3. 服务端：
   a. 校验 challenge 的签名和时效（同 §3.1）
   b. 只读查 (projectId, 110, keyId) 的绑定：无 → 404000；status=BLOCKED / RETIRED → 403002
   c. WebAuthn4J 验 assertion：存储公钥验签；rpIdHash；
      nonce = SHA256(authenticatorData ‖ SHA256(UTF8(clientData)))；counter > sign_count
   d. 一次性消费（同 §3.1-c；Redis 异常 → 跳过消费并打 ERROR）
   e. 事务内：UPDATE ... SET sign_count = :new, last_used_at = now
             WHERE id = :id AND status = 10 AND sign_count < :new
      影响 0 行 → 403001（并发重放或期间被封禁）；成功 → 用绑定的 installId 重签 installToken
→ 返回 CreateInstallResult
```

- recover 防重放的根本保证来自数据库里 counter 的条件更新，不依赖 Redis。Redis 消费只是额外加的一层。

- recover 只要求 `ios` 配置存在，与 mode 无关；未配置 → 400000。
- 旧 installToken 不吊销（install token 无状态、永不过期，与现状一致）。

---

## 4. 配置、开关与判定

### 4.1 开关

| 层 | 载体 | 默认 |
|---|---|---|
| 全局 kill switch | env `APP_ATTEST_GLOBAL_ENABLED` | false |
| challenge 签名密钥 | env `APP_ATTEST_CHALLENGE_SECRET`（`current[,previous]`，32 字节 base64） | 无；全局开关为 true 且缺少密钥 → 配置无效 |
| per-project | `core_project_server_config.app_attest_config`（JSONB，null = 关） | null |
| mode（project 级，单一） | `app_attest_config.mode`：OFF / OBSERVE / ENFORCE | OBSERVE |

```json
{
  "mode": "OBSERVE",
  "ios": {
    "teamId": "ABCDE12345",
    "bundleId": "com.example.antique",
    "env": "production",
    "deviceCheckKeyId": "KEYID12345",
    "deviceCheckPrivateKey": "-----BEGIN PRIVATE KEY-----..."
  },
  "android": {
    "packageName": "com.example.antique",
    "certSha256Digests": ["base64..."],
    "serviceAccount": { "type": "service_account" }
  }
}
```

- 平台子对象 = 该 provider 可验证；缺失时该 provider 的 proof → INVALID(provider_not_configured)。
- `mode=ENFORCE` 的有效条件：**至少配置了一个 provider，且所有已配置的子对象都能解析**。未配置的 provider 不会被放行：声明这种 provider 的 proof 一律判为 INVALID(provider_not_configured)，ENFORCE 下返回 403001。
  - 推论：ENFORCE 期间上线新平台的客户端（例如 Android），必须在发布前完成两件事：该平台客户端已经带上证明，服务端已经配置好对应子对象。否则这个平台的所有新 install 都会被拒。如果做不到，上线期间先把 mode 切回 OBSERVE。这一条写入发布检查清单（§9）。
- 配置不满足上述条件时，判定为**配置无效，fail-closed**：
  - createInstall / recoverInstall / createAttestChallenge 都返回 503002；
  - 日志节流，防止攻击者借此刷日志：
    - 每个 `projectId + configHash` 第一次发现时打一条 ERROR：`event=attest.config_invalid`，带上 desiredMode、缺失项、configHash、firstSeenAt；
    - 之后同一组合每分钟最多再打一条；
    - 配置修好后（configHash 变化，并且通过校验）打一条 INFO：`event=attest.config_recovered`；
    - 节流状态记在进程内存里，多实例时每个实例各打一份，可以接受。
  - 告警规则按 `attest.config_invalid` 配置；
  - **不**把 actuator 的整体 health 置为 DOWN：整体 DOWN 会导致实例被摘流量或重启，波及所有接口。一个 project 配错，不应拖垮全站。
  - 已经显式设成 ENFORCE，就不会因为配置错误自动放松。需要关闭时，由运维把 mode 改回 OBSERVE / OFF，或者关掉全局 kill switch。
- 服务端没有任何自动 fail-open 的路径（§4.3）。
- `deviceCheckPrivateKey`：Apple DeviceCheck .p8，仅 core-job 用。`serviceAccount`：开通 Play Integrity API 的 service account，单独存放。

### 4.2 provider 码

| provider | 含义 | 派生 `Install.platform` |
|---|---|---|
| 110 | APP_ATTEST | 20 IOS |
| 120 | PLAY_INTEGRITY | 10 ANDROID |

- provider 只表示**平台完整性证明**，当前只定义 110 / 120。以后新增平台（如微信小程序）时再分配编号；Int 协议不需要提前占号。人机验证（如 Turnstile）不是 provider：它可以和平台证明同时出现，以后要做的话，用单独的输入字段承载。

- 未实现的 provider → 400000；provider 与子对象不匹配 → 400000。
- VALID 时 `Install.platform` 由 provider 派生；其它情况沿用现状（取 header）。

### 4.3 判定矩阵

原则：**VALID 之前，provider、`x-client-platform`、`proofStatus` 都是客户端自报，不可信。** 因此只有一个 project 级 mode，不按 provider / header 选 mode。

`CreateInstallInput.proofStatus`（客户端自报，仅在未带 proof 时有意义）：
- 缺省：未启用证明（flag 关 / 设备不支持 / 旧版本）
- `20 = UNAVAILABLE`：客户端已尝试生成证明，但遇到临时故障（challenge 请求失败（网络 / 503）、Apple serverUnavailable 重试耗尽、Google 网络 / 429）

| 情形 | OFF | OBSERVE | ENFORCE |
|---|---|---|---|
| 带 proof，VALID | 不验证，放行 | 放行 + 持久化 | 放行 + 持久化 |
| 带 proof，INVALID | 不验证，放行 | 放行（不持久化） | 403001 |
| 带 proof，Redis 故障（一次性消费 / 去重做不了） | 不验证，放行 | 按其余校验结果处理（VALID 放行） | 按其余校验结果处理（VALID 放行）+ ERROR 日志 |
| 带 proof，Google 依赖 UNAVAILABLE（5xx / 429 / 超时，仅 1b） | 不验证，放行 | 放行 | 503002 |
| mode=ENFORCE 但配置无效 | — | — | 503002 |
| 无 proof，`proofStatus=UNAVAILABLE` | 放行 | 放行 | 503002 |
| 无 proof，未声明 | 放行 | 放行 | 403001 |

说明：

- **为什么取消 per-platform mode**：攻击者可以随意声明 provider 并提交垃圾 proof，所以 INVALID 时服务端不知道它真实属于哪个平台。按声明的 provider 选 mode，就等于让攻击者挑最弱的那个。结论：mode 是 project 级的，作用于所有已配置的 provider；新平台上线时遵守 §4.1 的推论。
- **为什么让客户端带 `proofStatus=UNAVAILABLE` 发请求**（偏离 review「临时失败不要发无 proof 请求」的建议）：
  - 客户端不知道服务端 mode。如果它在临时故障时干脆不发请求，那在 OBSERVE 下，只要 Apple 或 Google 长时间故障，就没有新用户能完成 bootstrap，可用性就被一个只是观察用的功能拖垮了。
  - 带上 `proofStatus=UNAVAILABLE` 发请求，由服务端决定怎么处理：OBSERVE 放行；ENFORCE 返回 503002，客户端按临时错误重试，不会把它误判成永久拒绝。
  - 攻击者伪造这个字段，只会把 ENFORCE 下的 403 变成 503，请求照样被拒，拿不到任何额外好处。
- **可用性优先：Redis 是弱依赖**（用户决定）。challenge 的签发和校验都是无状态的 HMAC 计算；Redis 只负责"一次性消费"和"Android token 去重"。Redis 故障时跳过这两步，请求照常放行，同时打节流后的 ERROR `attest.redis_degraded` 用于报警。这期间的敞口分析见 §3.1：同一份 attestation 被重放，会被 keyId 唯一约束拦下，不会多出 install。
- **Google API 故障不自动放行**（仅 1b）：Google 的 429 配额耗尽可以被分布式请求故意诱发，而且放行之后服务端对这次请求一无所知，等于完全没有校验。所以 ENFORCE 下返回 503002，由运维决定要不要切 mode 或关 kill switch。如果 1b 上线时你也希望这里按可用性优先处理，再单独评估。

### 4.4 错误码

| 码 | 枚举 | 含义 | 客户端 |
|---|---|---|---|
| 403001 | `ATTESTATION_FAILED` | createInstall：缺失 / 无效 / replay / key 已绑定（需要 recover）；attestExisting：并发 replay；recover：counter replay | 不做通用重试；按路径处理（§6.4、§6.7） |
| 503002 | `ATTESTATION_UNAVAILABLE` | Google 依赖故障（1b）、配置无效，或 ENFORCE 下客户端声明 UNAVAILABLE（Redis 故障不会触发） | 加入 `isTransient`，有限重试 |
| 404000 | `NOT_FOUND`（现有） | recover：key 未绑定 | 废弃 key（§6.4） |
| 404001 | `INSTALL_NOT_FOUND`（新增） | attestExisting：installToken 指向的 install 已不存在 | 见 §6.7（不是通用 404，客户端只在这个码上清 install） |
| 429000 | `RATE_LIMITED`（现有） | 入口短窗口超限，proof 尚未校验 | 带 `Retry-After`；在 challenge 有效期内可以重发同一份 proof |
| 403002 | `ATTEST_KEY_BLOCKED`（新增） | key 状态为 BLOCKED / RETIRED（attestExisting、recover） | 废弃 key，不 recover |
| 409001 | `ATTEST_KEY_BOUND_TO_OTHER_INSTALL`（新增） | attestExisting：这把 key 已经绑定在别的 install 上 | 废弃 key，**保留当前 installToken，不 recover** |
| 429002 | `INSTALL_DAILY_LIMITED`（新增） | 验签之后的日窗口超限（createInstall 专用） | 带 `Retry-After`（到 UTC 零点的秒数）；客户端废弃已 attest 的 key（§6.4） |

已核实：503002 未占用；503001 是客户端为「网关返回非 JSON」合成的 SERVICE_UNAVAILABLE，已在 `isTransient` 内。

### 4.5 切 ENFORCE 前置条件

1. iOS recover、客户端状态机（§6.4）、503002 重试、`proofStatus` 都已上线，并且**每个已发布平台**的新版本覆盖率达标；
2. §8 的灰度指标在**每个已发布平台**上都达标；
3. 每个已发布平台都配置了对应的 provider。目前已发布的只有 iOS，所以只需要 `ios`。
4. **平台发布清单（可审计）**：把所有已经分发过的平台、channel、TestFlight / internal track 列成清单，逐项确认对应的 provider 已配置、当前客户端版本会带上 proof。`app.config.js` 当前声明的是 `platforms: ['ios', 'android']`，EAS 里也有 Android profile，所以**只要有任何一个 Android 构建（包括 internal / preview）分发出去过，在 1b 完成之前就禁止切 ENFORCE**。Android 首次发布时，要么带上 1b 的 proof 并提前配置好 provider，要么在发布期间保持 OBSERVE。
5. `APP_LEGACY_INSTALL_ID_FALLBACK=false`，前提是旧版本 App 已经升级完。原因：fallback 打开时，旧客户端不带 installToken、只靠可伪造的 `x-install-id`，就能完成 createAnonymous / login，等于完全绕过了 createInstall 这道证明关口。

### 4.6 限流（IP 层 = 系统防护，install 层 = 防滥用）

**现状**：
- 本分支（`feature/attest`）的 `InstallFetcher` 只有一道短窗口：`checkFixedWindow("install:$clientIp", 10, 60)`。
- 50 次/IP/天的日窗口已经在 `feature/wire-encrypt`（`6c7d0f2`）实现，但还没合入。那份实现用的是首次 INCR 起算的滚动 24h fixed-window key。本规格会把它改成下面这种 UTC 日 bucket 的写法。

**原则**（用户决定）：两层限流，任一层超限即拒绝。
- **IP 层 = 系统防护**：粒度粗（同一出口 IP 后面可能有大量真实用户），阈值**大**，按系统容量来定。
- **install 层 = 防滥用**：粒度细，阈值**小**，略高于单个正常用户的用量上限即可。
- install 层能起作用的前提，是新建 install 有成本：createInstall 有 IP 日窗口；切到 ENFORCE 之后，每新建一个 install 还需要一台真机。所以单个 IP 每天的实际消耗上限为 `min(IP 日阈值, 每 IP 可建 install 数 × install 日阈值)`。
- attest 只影响 createInstall 的 IP 阈值。下游的 install 层**不区分是否已验证**，所以不需要 att claim，也不改 `signAccess` 和认证主链路。

**最终阈值**

| 动作 | install 层（防滥用） | IP 层（系统防护） |
|---|---|---|
| createInstall | —（install 还不存在） | 入口 100 / 60s；未验证 100 / 天；VALID 1000 / 天 |
| createAnonymous | 5 / install / 天 | 100 / 60s + 1000 / 天 |
| createScan | 5 / min + 100 / install / 天 | 100 / min + 1000 / 天 |
| DeepResearch | 5 / min + 100 / install / 天 | 100 / min + 1000 / 天 |

对照现状（只有 IP 层）：createInstall 10/60s；createAnonymous 10/60s；scan 5/min + 500/天；DeepResearch 3/min + 300/天。

**createInstall：日窗口放在验签之后，按验证结果选计数器**

```kotlin
rateLimiter.check(Window.MINUTE, "ratelimit:$pid:install:ip:min:$ip", cfg.install.ipMinute)   // 100/60s/IP，所有请求，在验签之前（保护验签消耗的 CPU）
val verification = attestGuard.verifyProof(ctx, input)                  // 纯技术验证，不套 mode
val decision = attestGuard.decideCreateInstall(verification, mode)      // 套用 §4.3 矩阵；这里抛出的 400/403/503 都不消耗日额度
val (bucket, limit) =
    if (decision.proofValid) "ratelimit:$pid:install:ip:day:attested:$ip"   to cfg.install.attestedIpDay     // 1000
    else                     "ratelimit:$pid:install:ip:day:unverified:$ip" to cfg.install.unverifiedIpDay   // 100
when (rateLimiter.check(Window.UTC_DAY, bucket, limit)) {               // 内部拼接 UTC 日期后缀
    Allowed, Degraded -> Unit
    Limited -> throw ApiError(ErrorCode.INSTALL_DAILY_LIMITED, "too many createInstall")   // 429002 + Retry-After
}
attestGuard.consume(decision)                                           // 日窗口通过后再一次性消费 challenge（SET NX）；replay → 403001
globalTx.withTx(ctx) { installFacade.createInstall(it, deviceInfo, storeType, decision.verifiedProof) }
```

- 日窗口统计的是「当天允许进入创建事务的尝试数」，不是成功创建的行数：
  - AttestGuard 阶段返回 403001 / 503002 / 400000：还没走到日窗口，不消耗日额度。
  - 通过 Guard、扣了日额度之后，在事务阶段失败（key_reused、并发唯一冲突、DB 错误）：额度**不退**。key_reused 会消耗一次 attested 额度，之后客户端改走 recover。
  - Redis 故障：放行，打 `ratelimit.degraded`。
  - 不引入退款机制。
- 两个日计数器相互独立，不共享总上限（这是有意的）：OBSERVE 下同一个 IP 每天最多 100 次未验证 + 1000 次已验证。ENFORCE 下非 VALID 请求都在 Guard 阶段被拒，实际只剩 1000 次 VALID。
- 短窗口没有按验证结果拆分：入口的 100/60s 对所有请求生效。
- **challenge 在日窗口通过之后才消费**：日窗口返回 429002 时，challenge 还没被消费，但是要等到 UTC 零点才会重置，所以客户端仍然废弃这把 key（§6.4）。并发提交同一份 proof 时，两个请求都能通过校验和日窗口（各扣一次额度），但只有一个能消费成功，另一个得到 403001(replay)。
- **Retry-After**：429000 返回短窗口的剩余秒数；429002 返回到 UTC 零点的秒数。

OBSERVE 下各种 proof 结果归入哪个计数器：

| proof 结果 | 计数器 |
|---|---|
| VALID | attested |
| INVALID（OBSERVE 放行） | unverified |
| 未带 proof | unverified |
| 客户端声明 UNAVAILABLE（OBSERVE 放行） | unverified |

**createAttestChallenge / recoverInstall**：只有短窗口，各自 10 / 60s / IP，单独计数。

**attestExisting**（§6.7）：10 / 60s / IP，加上 3 / install / UTC 日，单独计数。challenge 只做计算、不访问 Redis；recover 的防重放靠 counter 的条件更新。

**下游接口的 install 层**

- install 只认 token 里签名过的 `action.tokenInstallId`：
  - createAnonymous 用的是 installToken 里的 iid；
  - scan 和 DeepResearch 用的是 customer token 里的 iid。
- **不能**用 `mustGetTokenInstallId()` 或 `installIdOrNull()`，因为它们会回退到可伪造的 `x-install-id`。
- **三种策略**，按请求里有没有可信 iid 来选：

  | 情况 | 限流 |
  |---|---|
  | 有 `tokenInstallId` | install 层（额度小）→ IP 层（额度大，系统阀） |
  | 没有 `tokenInstallId`，兼容期仍走 legacy fallback | **legacy IP 层**：沿用现在的严格阈值，并且用单独的计数器，不占用上面那个大额 IP 计数器 |
  | 已关闭 legacy fallback 后，仍然没有 `tokenInstallId` | 在业务之前直接拒绝（401000。现有的 `mustGetTokenInstallId()` 在 fallback 关闭后本来就会这样拒绝） |

  这样 OBSERVE 兼容期内，旧客户端的攻击面不会被放大：legacy 请求的额度维持现状，拿不到新的大额 IP 阈值。
- **执行顺序：install 层 → IP 层 → 业务**。先挡住单个 install 的滥用，再占用共享的系统额度：
  - install 层拒绝时，不会去碰 IP 计数器。所以一个已经用完自己额度的 install 再怎么请求，也耗不掉同一个 IP 下其他 install 的额度。
  - IP 层拒绝时，install 层这次的额度已经扣掉了，**不退**。这是可接受的语义：只会多扣用户自己的一次尝试。
  - 不用 Lua 做"全部检查通过才一起扣"的原子预占（ponytail：多数场景先查 install 再查 IP 就够了；如果以后发现 IP 拒绝时误扣 install 额度成了问题，再升级成 Lua 原子预占）。
  - 被拒的请求都不退款。
- **key 按 projectId 隔离**：同一个出口 IP 访问 Project A，不会消耗 Project B 的额度；一个 project 也不能故意把另一个 project 的共享 IP 桶耗尽。本期**没有**全站级别的 IP 总闸。统一格式：
  ```
  ratelimit:{projectId}:{action}:ip:min:{ip}
  ratelimit:{projectId}:{action}:ip:day:{ip}:{yyyy-MM-dd}
  ratelimit:{projectId}:{action}:install:min:{iid}
  ratelimit:{projectId}:{action}:install:day:{iid}:{yyyy-MM-dd}
  ratelimit:{projectId}:{action}:legacy:ip:min:{ip}
  ratelimit:{projectId}:{action}:legacy:ip:day:{ip}:{yyyy-MM-dd}
  ```
  现有 `AiFetcher` / `CustomerFetcher` / `InstallFetcher` 的 key 都没有带 projectId，这次一起改。新 key 是从零开始计数的，上线当天额度会相当于重置一次，可以接受。
- 同一个 install 下所有匿名 customer 和已登录 customer 共用一份 install 额度，所以靠新建多个匿名 customer 不能扩大额度。

**成本**：`ScanQuotaConfig` 的终身配额默认是 `Int.MAX_VALUE`，不能当兜底。AI 成本由上面两层的日窗口控制：单个 IP 每天最多 1000 次 scan 加 1000 次 DeepResearch；单个 install 每天最多各 100 次。

**容量提醒**：scan 和 DeepResearch 都是同步执行的长请求，单次 2~3 分钟。IP 层 100/min 意味着，单个 IP 同时在跑的 AI 调用理论上可以达到几百个。上线前要和 AI key 池容量、虚拟线程 / 连接池上限一起核对；如果不够，就调低 IP 的分钟阈值（只改配置）。

**成本告警**：上线前给 AI 调用量和成本配置告警，并约定好回滚阈值。限流配置要**重启才生效**，不能当即时的 kill switch，紧急降额需要走一次重启或发布。

**配置**：每个 action 显式配置，不使用统一倍数，重启生效，不做热更新。现在 `AiFetcher` / `CustomerFetcher` / `InstallFetcher` 里写死的常量都改为读取配置。

```yaml
app:
  ratelimit:
    install:
      ip-minute: 100
      unverified-ip-day: 100
      attested-ip-day: 1000
    attest-challenge:
      ip-minute: 10
    recover-install:
      ip-minute: 10
    attest-existing:
      ip-minute: 10
      install-day: 3
    anonymous:
      legacy-ip-minute: 10       # 没有 tokenInstallId 的请求：沿用现有阈值
      ip-minute: 100
      ip-day: 1000
      install-day: 5
    scan:
      legacy-ip-minute: 5
      legacy-ip-day: 500
      ip-minute: 100
      ip-day: 1000
      install-minute: 5
      install-day: 100
    deep-research:
      legacy-ip-minute: 3
      legacy-ip-day: 300
      ip-minute: 100
      ip-day: 1000
      install-minute: 5
      install-day: 100
```

`RateLimiter` 接口统一（短窗口和日窗口用同一套返回类型，也共用同一个降级日志控制器）：

```kotlin
sealed interface RateLimitResult {
    data object Allowed : RateLimitResult
    data object Limited : RateLimitResult     // → RATE_LIMITED / INSTALL_DAILY_LIMITED，带 Retry-After
    data object Degraded : RateLimitResult    // Redis 故障，放行 + 节流后的 ERROR（见下）
}
enum class Window { MINUTE, UTC_DAY }
fun check(window: Window, key: String, limit: Int): RateLimitResult
```

- 现有返回 Boolean 的 `checkFixedWindow` 的所有调用方（`InstallFetcher`、`CustomerFetcher`、`AiFetcher`）都迁移到新接口，然后删除 `checkFixedWindow`，避免实现时只改了日窗口、漏掉短窗口。基于 tier 的 `check(ctx, subject)` / `refund` 只有 `RateLimiterTest` 在用，没有生产调用方，不在本期范围内（保持不动）。

**限流的 Redis 故障：放行，并打 ERROR 日志用于报警**

- `RateLimiter.check` 返回 `Degraded`（INCR 返回 null 或抛异常；短窗口和日窗口同样处理）时，请求照常放行。
- 打 ERROR 日志：`event=ratelimit.degraded`，字段包括 subject 前缀（如 `install:day`）、windowSec、异常类型，不带 IP 原文。
- 日志节流：每个 subject 前缀第一次出现时打一条，之后每分钟最多一条；Redis 恢复后（第一次 INCR 成功）打一条 INFO `event=ratelimit.recovered`。
- 现有 `RateLimiter` 在降级时打的是 WARN。这次统一改成上面的 ERROR + 节流，**会影响所有使用限流的地方**（`AiFetcher`、`CustomerFetcher` 等），1a 实现时一起改。
- attest 链路的 Redis 故障也按"放行 + ERROR"处理（§3.1、§4.3）。日志 event 分开：限流用 `ratelimit.degraded`，attest 用 `attest.redis_degraded`。

---

## 5. 服务端设计

### 5.1 GraphQL

```graphql
input InstallProofInput {
    "110=APP_ATTEST / 120=PLAY_INTEGRITY"
    provider: Int!
    appAttest: AppAttestProofInput
    playIntegrity: PlayIntegrityProofInput
}
input AppAttestProofInput {
    keyId: String!
    attestationObject: String!
    challenge: String!
}
input PlayIntegrityProofInput {
    integrityToken: String!
    nonce: String!
}
input RecoverInstallInput {
    keyId: String!
    assertion: String!
    challenge: String!
}
type AttestChallengeResult {
    "false = 服务端当前不需要证明（全局开关关闭 / project 未配置 / mode=OFF / 平台未配置）：客户端不要生成 key，直接走 no-proof"
    enabled: Boolean!
    "enabled=false 时为 null"
    challenge: String
    "客户端可用的时间预算（270s），服务端实际接受 300s，留出传输和 attest 的余量"
    expiresInSec: Int!
}

type CreateInstallResult {
    installId: UUID!
    installToken: String!
    "10=VERIFIED_PERSISTED（证明有效且已绑定）/ 20=NOT_PERSISTED（带了 proof 但没有绑定：OBSERVE 下 INVALID 等）/ 30=NOT_ATTEMPTED（没带 proof 或服务端未校验）。只有 10 能让客户端进入 REGISTERED"
    attestationStatus: Int!
}

input CreateInstallInput {
    deviceInfo: JSON
    "平台证明；是否必填由服务端 mode 决定"
    proof: InstallProofInput
    "未带 proof 时的客户端自报状态：缺省=未启用 / 20=UNAVAILABLE（临时故障）。见 §4.3"
    proofStatus: Int
    "安装来源商店：10=APP_STORE / 20=GOOGLE_PLAY。客户端按构建渠道写死；write-once。见 §5.9"
    storeType: Int
}

extend type Mutation {
    "无鉴权；独立的 10/60s/IP 短窗口。一次性 challenge，服务端接受 300s，对客户端返回 270s。"
    m_install_createAttestChallenge: AttestChallengeResult!
    "无鉴权；独立的 10/60s/IP 短窗口。iOS 用 assertion 证明 key 所有权，重签已绑定 install 的 installToken。返回的 attestationStatus 固定为 10。"
    m_install_recoverInstall(input: RecoverInstallInput!): CreateInstallResult!
}
```

不按平台拆 createInstall：各平台业务相同；按平台观测用日志维度（§5.5）。

`proof` / `proofStatus` 组合校验（入口完成，先于 Guard）：

| proof | proofStatus | 结果 |
|---|---|---|
| 非空 | 非空 | 400000 |
| 空 | 20 | 客户端声明 UNAVAILABLE |
| 空 | 空 | 未带证明（missing / disabled） |
| 任意 | 不是 20 的其它值 | 400000 |

### 5.2 组件

遵循 AGENTS.md 分层；外部验证在事务外，绑定与写入在事务内。

| 单元 | 路径 | 职责 |
|---|---|---|
| `AttestConfig` | `infra/attest/` | 配置 DTO + 解析 + ENFORCE 完整性检查 |
| `ProjectServerConfig` / `ProjectServerConfigFacade` | 现有 | + `appAttestConfig` 列；`findAttestConfig(projectId)` |
| `AttestChallengeCodec` | `infra/attest/` | 签发 / 校验 HMAC challenge（§3.1），纯计算 |
| `AttestReplayGuard` | `infra/attest/` | `markUsed(key, ttl)`：SET NX EX，返回 `FIRST / REPLAY / DEGRADED`；DEGRADED 时打节流后的 ERROR |
| `AppAttestVerifier` | `infra/attest/` | attestation / assertion 的 WebAuthn4J 验证（纯密码学）。production 和 development 是两个独立实例（`DCAttestationDataVerifier.production` 分别为 true / false），按 `ios.env` 选用 |
| `AppAttestTrustAnchors` | `infra/attest/` | 从 classpath 资源 `attest/apple-app-attestation-root-ca.pem` 加载 Apple App Attestation Root CA，**启动时校验 SHA-256 指纹是否与代码里固定的常量一致**，不一致就启动失败。构造 `KeyStoreTrustAnchorRepository` + `DefaultCertPathTrustworthinessVerifier`。**不能**用 JVM 系统信任库，也不能用 Null verifier。Apple 新增或轮换根证书时，更新资源和指纹常量，随版本发布 |
| `PlayIntegrityVerifier` | `infra/attest/` | decode + 校验 + token 去重 |
| `AttestGuard` | `infra/attest/` | 拆成三个职责清楚的方法，各自只有一套 INVALID 语义：`verifyProof(...)`：纯技术验证（challenge 格式/签名/时效、WebAuthn4J、信号量），**不套用 mode**，返回 VALID / INVALID / UNAVAILABLE；`decideCreateInstall(verification, mode)`：createInstall 用，套用 §4.3 的矩阵（ENFORCE + INVALID → 403001）；`consume(verification)`：一次性消费 challenge。attestExisting 不调用 mode 决策，由 Fetcher 自己把结果映射成 10/20/30（§6.7）。**不写业务表**；recover / attestExisting 在验签前需要读公钥、绑定和状态时，通过只读的 `InstallFacade.findAttestationByKey` 获取（infra → Facade 只读，有现成先例：`FirebaseAppRegistry → ProjectServerConfigFacade`） |
| `InstallAttestation` | `entity/install/` | 持久凭证实体 |
| `InstallAttestationRepository` | `modules/install/repo/` | 插入 / 按 subject 查 / 条件更新 counter |
| `InstallAggHandler` | `modules/install/handler/` | `createInstall(mc, deviceInfo, proof?)`；`recoverInstall(mc, attestationId, newCounter)` |
| `InstallFacade` | 现有 | 透传 + `findAttestationByKey` |
| `InstallFetcher` | 现有 | 限流 → Guard → `globalTx { facade... }`；新增两个 mutation |
| `ErrorCode` | 现有 | + `ATTESTATION_FAILED`(403001)、`ATTEST_KEY_BLOCKED`(403002)、`ATTEST_KEY_BOUND_TO_OTHER_INSTALL`(409001)、`INSTALL_NOT_FOUND`(404001)、`ATTESTATION_UNAVAILABLE`(503002)、`INSTALL_DAILY_LIMITED`(429002) |

```kotlin
data class VerifiedProof(
    val provider: Int,
    val subject: String?,            // iOS keyId；Android null
    val publicKey: ByteArray?,
    val receipt: ByteArray?,
    val signals: Map<String, Any?>,  // 固定键集合，§5.7
    val evidence: Map<String, Any?>, // 不含原始 token
)
```

### 5.3 事务内绑定（createInstall）

```
globalTx {
  if proof == null 或 proof.subject == null:
      创建 install；Android VALID 则写 attestation 行（subject = null）
  else:  // iOS VALID
      existing = 按 (projectId, 110, keyId) 查
      existing == null → 创建 install + attestation 行
      existing != null → 403001(key_reused)，不创建 install（与 mode 无关）
}
```

- key_reused 是身份绑定一致性问题，不受 OBSERVE / ENFORCE 影响；客户端收到后走 recover，拿回已绑定的 install。
- 并发同 keyId（两个不同 challenge）：唯一约束兜底，失败方回滚 → 403001。
- `install.platform` 按 §4.2 派生。

### 5.4 数据模型

```sql
ALTER TABLE core_project_server_config ADD COLUMN app_attest_config JSONB NULL;
ALTER TABLE core_install ADD COLUMN store_type INT NULL;   -- §5.9

-- 只存 VALID 的长期凭证 / 绑定
CREATE TABLE core_install_attestation (
    id                    UUID PRIMARY KEY,          -- UuidV7
    project_id            TEXT NOT NULL,
    install_id            UUID NOT NULL,             -- 逻辑外键 core_install.id
    provider              INT  NOT NULL,             -- 110 / 120
    subject               TEXT NULL,                 -- iOS keyId；Android NULL
    public_key            BYTEA NULL,
    sign_count            BIGINT NOT NULL DEFAULT 0, -- iOS 首次 attestation 写 0；recover 的 `sign_count < :new` 依赖它不为 NULL
    receipt               BYTEA NULL,
    receipt_expires_at    TIMESTAMPTZ NULL,
    next_refresh_at       TIMESTAMPTZ NULL,
    refresh_failure_count INT NOT NULL DEFAULT 0,
    fraud_metric          INT NULL,
    signals               JSONB NOT NULL,
    evidence              JSONB NULL,                -- 90 天后清空（§5.7）
    status                INT NOT NULL,              -- 10 ACTIVE / 20 BLOCKED（风险封禁）/ 30 RETIRED（超出每个 install 的 ACTIVE 上限后轮换下来的，不能 recover，也不能复活）
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    last_used_at          TIMESTAMPTZ NULL
);
CREATE UNIQUE INDEX uk_install_attestation_subject
    ON core_install_attestation (project_id, provider, subject) WHERE subject IS NOT NULL;
CREATE INDEX idx_install_attestation_install ON core_install_attestation (project_id, install_id);
CREATE INDEX idx_install_attestation_refresh ON core_install_attestation (next_refresh_at) WHERE receipt IS NOT NULL;
CREATE INDEX idx_install_attestation_evidence ON core_install_attestation (created_at) WHERE evidence IS NOT NULL;
```

- 失败尝试不入表，只进日志（§5.5）。日志不够分析时再加 `core_install_attestation_attempt`（ponytail：先用日志，升级路径明确）。
- **`status=BLOCKED` 在一期的实际效果只有「禁止 recover，禁止这个 key 做新的绑定」。** installToken 是无状态、永不过期的 JWT，认证链路不会查 attestation 状态，所以已经签发出去的 token 照样能用。这里不能说成"封禁"了这个 install。真正的封禁需要的升级路径见 §8 二期。

### 5.5 观测（结构化日志 + SQL）

core-api 有 actuator，但未接指标导出；现有观测是 logstash JSON 日志（自带 MDC：plat / av / pid / ip / rid）。

```
event=install.attest   mode provider proofStatus result=missing|valid|invalid|unavailable|client_unavailable reason verifyMs totalMs signals{固定键}
event=install.recover  result=ok|not_found|invalid|blocked|unavailable reason verifyMs
```

- `reason` 为有限枚举：challenge_missing / replay / chain_invalid / nonce_mismatch / rp_mismatch / counter / not_play_recognized / cert_mismatch / device_integrity / stale / request_hash / key_reused / provider_not_configured / config_invalid / store_mismatch / redis / google_5xx / google_429 / google_timeout …
- 不打印 token、attestationObject、assertion、keyId 原文（必要时打 keyId SHA256 前 8 字节）。
- 成功率 / 耗时按 provider、plat、av 在日志平台聚合；VALID 信号分布用 SQL 统计 `signals`；「无 proof 比例」用 `core_install` 左连接 attestation 表按 platform 统计。

### 5.6 依赖

| 模块 | 依赖 | 说明 |
|---|---|---|
| core-api | `com.webauthn4j:webauthn4j-appattest`（固定版本，可见 0.30.1.RELEASE） | 新增；核实 JDK 25 / Jackson 共存 |
| core-api | Google OAuth2（`GoogleCredentials`） | 已随 firebase-admin 在 classpath |
| core-api | Apple App Attestation Root CA（PEM 资源） | 新增资源文件，从 Apple Private PKI 下载，指纹固定在代码里（见 `AppAttestTrustAnchors`） |
| core-job | `com.nimbusds:nimbus-jose-jwt:9.40` | 单独声明；DeviceCheck ES256 JWT |
| core-job | Spring Boot Jackson starter | 新增；解析 JSONB 与 Apple 响应 |
| core-job | JDK `HttpClient`、现有 `JdbcTemplate` | 无新增 |

DeviceCheck 最小配置 DTO 在 core-job 内单独定义，不放 core-common。

### 5.7 输入限制、信号与保留

**输入上限**（GraphQL 入口校验，超限 400000；初值，按真机样本校准）：keyId 64 字符；challenge 128 字符（当前格式 76）；nonce 64 字符；attestationObject 16 KB；assertion 4 KB；integrityToken 16 KB。

**signals 固定键**：
- iOS：`env`、`fraudMetric`（后台写入）
- Android：`appRecognition`、`deviceRecognition[]`、`deviceActivityLevel`、`licensing`、`playProtect`、`appAccessRisk[]`、`deviceRecall{bit1,bit2,bit3}`、`versionCode`

**保留**：

| 数据 | 保留 |
|---|---|
| keyId、public_key、sign_count、status、signals | 跟随 install（install 清理设计时纳入） |
| receipt | 跟随 key；Apple 过期后由刷新任务替换 |
| evidence（原始 verdict / 证书摘要） | **90 天**后清空（core-job 每日：`UPDATE ... SET evidence = NULL WHERE evidence IS NOT NULL AND created_at < now() - 90d`） |
| integrityToken 原文 | 不存储（Redis 仅存 hash，TTL 600s） |

- `app_attest_config` 含私钥与 service account，沿用 `core_project_server_config`「服务端专属、绝不下发」约束。
- App Access Risk、Device Recall 仅用于反滥用。

### 5.8 core-job 任务

**iOS fraud metric 刷新**
- 选取 `provider=110 AND status=10 AND receipt IS NOT NULL AND next_refresh_at <= now()`（新行 `next_refresh_at` 初始化为 receipt 的 not-before / 下次允许刷新时间）。
- DeviceCheck JWT（ES256）POST receipt 到 Apple attestation data 端点（按 `ios.env`）。
- 成功：写新 `receipt`、`receipt_expires_at`、`fraud_metric`、`next_refresh_at`（取响应中的下次允许刷新时间），`refresh_failure_count = 0`。
- 429 / 5xx：`refresh_failure_count + 1`，`next_refresh_at = now + 指数退避`（封顶 24h），不改 receipt。
- 一期只写入，不封禁。

**evidence 清理**：见 §5.7，每日一次。

### 5.9 Install 的 storeType

`core_install.store_type INT NULL` 记录安装来源商店。

| 值 | 含义 | 客户端 |
|---|---|---|
| 10 | APP_STORE | iOS 构建一律传 10（TestFlight / 开发构建也传 10） |
| 20 | GOOGLE_PLAY | Android 的 Google Play 构建（1b） |
| 其它 | 未定义 | 以后新增渠道（如国内安卓商店、侧载）再分配 |

- **来源**：`CreateInstallInput.storeType`，由客户端按构建渠道写死。按项目约定，枚举用 Int 贯穿全链路。
- **校验**：缺省 → 存 NULL（兼容不传这个字段的旧版本）；取值不在 {10, 20} 内 → 400000。
- **write-once**：只在 createInstall 时写入，`updateInstall` 不修改。recoverInstall 返回的是已存在的 install，同样不修改。
- **可信度**：这是客户端自己报的值，只用于统计，不参与鉴权或支付判断。VALID 证明可以做交叉核对，不一致时只打日志 `reason=store_mismatch`，不拒绝请求，也不改写这个值：
  - provider 110 VALID，但 storeType ≠ 10；
  - 1b 中 provider 120 VALID，且 appRecognitionVerdict 是 PLAY_RECOGNIZED，但 storeType ≠ 20。
- **和 platform 的关系**：两者各自独立。platform 表示操作系统（VALID 时由 provider 推出来）；storeType 表示分发渠道。同一个 platform 以后可能对应多个 storeType，例如 Android 的不同商店。

---

## 6. 客户端设计

### 6.1 依赖与原生配置

- `@expo/app-integrity`（ALPHA，固定版本）。
- `app.config.js`：`ios.entitlements['com.apple.developer.devicecheck.appattest-environment']` 按 `APP_ENV` 取值：

  | APP_ENV | entitlement | 对应服务端 `ios.env` |
  |---|---|---|
  | development | `development` | 开发环境 project 配 `development` |
  | preview / production（Archive → TestFlight / App Store） | `production` | 生产 project 配 `production` |

  > 需核实：Apple 文档称 TestFlight / App Store 分发的 App 一律使用 production 环境，entitlement 只影响开发构建。服务端 `ios.env`、AAGUID 校验与二进制 entitlement 三者必须一致；dev 构建连生产服务器会因 AAGUID 不符 → INVALID。
- 该库没有 config plugin（已核实），entitlement 只能手动配置。
- `runtimeVersion: '2'` → `'3'`，重新 prebuild + Archive，不可只发 OTA。
- `lib/config.ts`：`EXPO_PUBLIC_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER`。

### 6.2 组件与接口

| 单元 | 路径 | 职责 |
|---|---|---|
| attest flag | `apps/antique/src/features/attest/flags.ts` | 照 `push/flags.ts`；`DEFAULT_ATTEST_ENABLED=false`；override `devAttestEnabled` |
| dev 开关 | `apps/antique/src/app/dev-settings.tsx` | + 开关 |
| 平台封装 + 状态机 | `apps/antique/src/lib/attest.ts` | 唯一 import `@expo/app-integrity`；`initAttestation(ctx)`；实现 `InstallProofProvider` |
| api 接线 | `apps/antique/src/lib/api.ts` | 注入 `installProof` |
| shared | `apps/shared/src/api/client.ts` | `ServerApiOptions.installProof?`；ensureInstall 编排 create / recover |
| GraphQL op | `apps/shared/src/api/graphql.ts` | + createAttestChallenge、recoverInstall；createInstall 传 proof / proofStatus / storeType（取自 `lib/config.ts` 的 `storeType`：iOS 固定 10，Android Play 构建为 20） |
| 错误码 / 重试 | `apps/shared/src/api/codes.ts`、`retry.ts` | + 403001、503002；503002 进 `isTransient` |
| 启动 | `_layout.tsx`、`features/install/ensureInstallWhenOnline.ts` | loadEnvOverride → initAttestation → ensureInstallWhenOnline 串行；调度按 §6.8 |

shared 接口（不 import 原生库）：

```ts
type ProofStep =
  | { kind: 'create'; proof: InstallProofInput }
  | { kind: 'recover'; input: RecoverInstallInput }
  | { kind: 'attest_existing'; proof: InstallProofInput }   // 已有 installToken 的存量补证（§6.7）
  | { kind: 'no-proof' }                       // flag 关 / 设备不支持 → createInstall 不带 proof、不带 proofStatus
  | { kind: 'unavailable'; cause: unknown };   // 临时故障 → createInstall 带 proofStatus=20

type ProofOutcome =
  | 'create_verified'            // create 成功且 attestationStatus=10
  | 'create_unverified'          // create 成功但 attestationStatus=20/30（install 已建好，key 没有绑定）
  | 'recover_registered'         // recover 成功
  | 'replay_or_reused'           // create 返回 403001
  | 'recover_not_found'          // recover 返回 404000
  | 'rejected'                   // recover 返回 403001
  | 'rate_limited_pre_verify'    // 入口短窗口 429000：proof 还没被校验和消费
  | 'rate_limited_post_verify'   // 验签后的日窗口 429002：proof 已校验但未绑定
  | 'ambiguous';                 // 网络错误 / 超时，结果未知

interface InstallProofProvider {
  next(ctx: {
    operation: 'bootstrap' | 'backfill';
    install: InstallSnapshot;                    // { installId, epoch } | null，由协调器在锁内取快照后传入
    fetchChallenge: () => Promise<ChallengeResult>;
  }): Promise<ProofStep>;
  report(outcome: ProofOutcome): Promise<void>;
}
```

ensureInstall（在现有 single-flight 内）：

```
retryTransient(async () => {
  step = await installProof.next(...)
  try   { result = step 对应的请求；await report(按 result.attestationStatus 映射 outcome); return result }
  catch (e) { await report(按错误映射 outcome); throw e }   // 503002 / 网络错误为 transient → 重试
})
```

- `fetchChallenge` 返回 503002：在 `next()` 内转为 `{ kind: 'unavailable' }`，不吞成 no-proof。
- `fetchChallenge` 返回 `enabled=false`：`next()` 返回 `no-proof`，**不生成 key**，状态保持 EMPTY。这样服务端 OFF 时不会白白生成 key、抬高 fraud metric。
- **HTTP 200 只说明 install 已创建，不代表 key 已经绑定**。状态转移完全看 `attestationStatus`。
- 403001 / 404000 不进入 `retryTransient` 的重试。处理方式：`report` 推进状态后，**在同一次 bootstrap 内立即按新状态再执行一步**，最多 2 步。例如 create 返回 replay_or_reused 后立即 recover；recover 返回 not_found 后立即用新 key 重新 create。还失败就抛出，交给 `ensureInstallWhenOnline` 的定时重试。

### 6.3 启动顺序（不在模块加载时初始化）

不导出"模块一加载就执行"的 `attestReady`。原因：`attest.ts` 可能在 `api.ts` 的依赖链里被提前加载，那时 `loadEnvOverride()` 还没执行，它会读到默认的 prod 上下文，导致状态、challenge、proof 和最终的 API 环境对不上。

改为一个**惰性、幂等**的初始化函数，参数是这一次 bootstrap 的**不可变上下文快照**：

```ts
interface AttestContext {
  projectId: string;
  apiEnv: AppEnv;                                         // loadEnvOverride() 之后的值
  appAttestEnvironment: 'development' | 'production';     // 和二进制里的 entitlement 一致（构建时注入）
  appVersion: string;
}
export function initAttestation(ctx: AttestContext): Promise<void>;   // 幂等；同一个进程内 ctx 不能变，变了就抛错
```

`_layout.tsx` 的启动顺序改成一条严格串行的链。现在的写法是两个相互独立的 effect：一个负责加载本地状态，另一个直接调用 `ensureInstallWhenOnline`。新的顺序是：

```ts
useEffect(() => {
  let cleanup: (() => void) | undefined;
  void (async () => {
    await loadEnvOverride();
    // …… 现有的本地初始化 ……
    await initAttestation(currentAttestContext());        // 读取已经加载好的 apiEnv
    cleanup = ensureInstallWhenOnline(() => api.installEnsure());
  })();
  return () => cleanup?.();
}, []);
```

- 在 `ensureInstallWhenOnline` 启动之前，不发出任何 install、challenge 或 proof 请求。
- Android 的 `prepareIntegrityTokenProviderAsync` 放在 `initAttestation` 里执行，带超时，失败也不会 reject（1b）。
- **回归测试**：本地已经有 `apiEnv=dev` 的覆盖值时，第一次 challenge / create 请求一定发到 dev，不会发到默认的 prod。
### 6.4 iOS 状态机（`lib/attest.ts`）

**存储位置**：单条 JSON 记录存 **AsyncStorage**（key `attestState`），不存 SecureStore。偏离 review「keyId 存 SecureStore」的建议，理由如下：
- keyId 只是 Secure Enclave 公钥的 hash，attestationObject 也不是秘密；私钥永远不出 SE，存哪里都不影响安全。
- iOS 上 keychain 在卸载后仍会保留，而 App Attest key 卸载后就失效了。如果存在 SecureStore，重装后读到的是一个已经不能用的 keyId；存 AsyncStorage，它会跟着 App 一起被删掉，和 key 的生命周期正好一致。
- 只用一个存储、每次一次 JSON 写入，不会出现两个存储之间状态不一致的问题。expo-secure-store 对单个值有大约 2KB 的限制，也放不下几 KB 的 attestationObject。

**状态**

```
EMPTY
KEY_READY        { keyId }                                         // 已生成，从未调用 attestKey
ATTESTING        { keyId }                                         // 即将 / 正在调用 attestKey（意图日志）
ATTESTED_PENDING { keyId, challenge, attestationObject, challengeExpiresAt, targetInstallId? }   // targetInstallId 仅补证时有
RECOVER_PENDING  { keyId, targetInstallId? }                       // key 已 attest，下一轮只能走 recover
REGISTERED       { keyId, installId }                              // 只有 attestationStatus=10 或 recover 成功才进入；installId 是绑定的那个 install
UNSUPPORTED      {}                                                // 设备不支持 App Attest：终态，不再尝试
```

状态机在内存中保存一份当前状态，`next()` 读的是内存里这份；每次转移都会同步写入 AsyncStorage。

**规则 1：调用 `attestKeyAsync` / `generateAssertionAsync` 之前，必须先把对应的意图状态写入存储；写失败就不调用，停在原状态，返回 `unavailable`。**
用来保证不会对同一把 key 做第二次 attest，也不会出现「attest 结果未知、又没有任何记录」的情况。

`generateKeyAsync` 是唯一的例外：调用前还拿不到 keyId，所以没有可以先写入的状态。它成功之后才写 KEY_READY。如果这次写入失败（或在写入前崩溃），会留下一把未 attest 的孤儿 key。这把 key 不会计入 fraud metric（fraud metric 统计的是 attested key），流程随后回到 EMPTY。

**规则 2：`report` 的状态转移先更新内存，再写存储。** 写存储失败时，本进程继续按内存状态走，并打一条 `pending_state_persist_failed` 日志。重启后会从磁盘上较旧的状态恢复，这种情况最多导致多发一次请求，不会造成安全问题（例子见下方结果表后的说明）。

```
EMPTY
  └─ generateKeyAsync() → 写 KEY_READY（失败 → 丢弃此 key，返回 unavailable）
KEY_READY
  └─ fetchChallenge（503 → unavailable）
     → 写 ATTESTING（失败 → unavailable）
     → attestKeyAsync(keyId, challenge)
          ERR_APP_INTEGRITY_SERVER_UNAVAILABLE（attest 本身没成功）→ 回写 KEY_READY → unavailable（下一轮用同一把 key 重试，符合 Apple 指引）
          ERR_APP_INTEGRITY_FEATURE_UNSUPPORTED → 写 UNSUPPORTED（终态），返回 no-proof；以后不再尝试、不做定时重试（`isSupported=false` 时直接进入 UNSUPPORTED，不生成 key）
          ERR_APP_INTEGRITY_SYSTEM_FAILURE / UNKNOWN → 本次 bootstrap 内用同一 key 再短暂重试 1 次；仍失败 → 废弃 key → EMPTY（遵循 Apple 指导：除 SERVER_UNAVAILABLE 外的错误都应丢弃 key）
          ERR_APP_INTEGRITY_INVALID_KEY / INVALID_INPUT → 废弃 key → EMPTY（每次 bootstrap 最多新建 1 把 key）
          超时（ATTEST_TIMEOUT_MS，初值 15s，用真机校准）→ 本次 attempt 作废，状态保持 ATTESTING，返回 unavailable；下一轮按 ATTESTING 处理（转 RECOVER_PENDING → 一般 404 → 废弃 key）
          成功 → 写 ATTESTED_PENDING
               写失败 → 打 pending_state_persist_failed；本次仍用内存中的 proof 发请求；磁盘上停在 ATTESTING
             → 返回 create(proof)
ATTESTED_PENDING
  ├─ now < challengeExpiresAt → 重发同一份 proof（create）
  └─ 已过期 → 转 RECOVER_PENDING
ATTESTING（重启后发现：attestKey 的结果未知）
  └─ 转 RECOVER_PENDING（如果这把 key 其实没 attest 成功，assertion 会在本地失败或服务端返回 404 → 废弃 key）
RECOVER_PENDING
  └─ fetchChallenge → generateAssertionAsync → 返回 recover
REGISTERED
  └─ 本地已有 installToken 时不进入 proof 流程；本地凭证丢失时转 RECOVER_PENDING
UNSUPPORTED
  └─ 永远返回 no-proof。OBSERVE 下照常创建未验证的 install；ENFORCE 下会被 403001 拒绝（产品策略：不支持 App Attest 的 iOS 设备在 ENFORCE 下不能注册，这类设备需要 iOS 14+ 且有 Secure Enclave，实际极少）
```

**迟到结果**：每次调用原生方法都带一个 `attemptId`（单调递增的 epoch）。超时后这个 attempt 就作废了，原生调用迟到的结果（成功或失败）都直接丢弃，不改写状态，避免把一个已经推进到别处的状态又改回 ATTESTED_PENDING。`generateAssertionAsync` 也一样，同样有超时和 attemptId。

**结果处理（report）**

| 当前路径 | outcome | 转移 |
|---|---|---|
| create | create_verified | → REGISTERED |
| create | create_unverified | install 已创建、key 没绑定。这把 key 已经被 Apple attest 过，不能再 attest → **废弃 key → EMPTY**（后续是否补证见 §6.7） |
| create | rate_limited_pre_verify（429000，入口短窗口） | 保持 ATTESTED_PENDING；按 Retry-After 等待，在 challenge 有效期内重发同一份 proof，过期则转 RECOVER_PENDING |
| create | rate_limited_post_verify（429002，验签后的日窗口） | proof 已经校验过但没有绑定，日窗口要到 UTC 零点才重置 → **废弃 key → EMPTY**，等到 Retry-After 再开始 |
| create | ambiguous | 保持 ATTESTED_PENDING（下一轮在有效期内重发，过期转 RECOVER_PENDING） |
| create | replay_or_reused | → **RECOVER_PENDING**（下一轮一定走 recover，不会再 create） |
| recover | recover_registered | → REGISTERED |
| recover | ambiguous | 保持 RECOVER_PENDING |
| recover | recover_not_found | 这把 key 已经 attest 过，但服务端从没注册它 → **废弃 key → EMPTY**（绝不对它再次 attest） |
| recover | rejected | 403002（key 被封禁或已退役），或 403001（counter 异常）→ 废弃 key → EMPTY |
| recover | 本地 generateAssertion 报 invalid key | 废弃 key → EMPTY |

- **一把 key 只会 attest 成功一次**，之后只用 assertion。只有 `SERVER_UNAVAILABLE`（这次 attest 本身没成功）时，才会对同一把 key 再调一次 attestKey。
- 单次 bootstrap 内，最多废弃并新建 1 把 key，防止进入循环。
- 网络重试不会生成新 key：`retryTransient` 每一轮都调用 `next()`，`next()` 按当前状态决定重发、recover 还是 attest，只有 EMPTY 状态才会新建 key。
- 规则 2 下写存储失败的最坏情况：RECOVER_PENDING 没写进磁盘，进程又重启了。磁盘上还是 ATTESTED_PENDING，如果 challenge 还没过期，就会多发一次 create，得到 403001 后转 RECOVER_PENDING。如果 challenge 已经过期，就直接走 recover。两种情况都不会对 key 第二次 attest。
- 重装后 AsyncStorage 被清空，回到 EMPTY，正常生成新 key。
- ATTESTED_PENDING 写失败之后，如果进程又崩溃，而且 create 请求没到达服务端，那么重启后会从 ATTESTING 转 recover，得到 404，最后废弃一把已经 attest 过的 key。这不是安全问题，但会让 fraud metric 偏高一点，由 `pending_state_persist_failed` 日志计数（§8）。

### 6.5 Android

- 每次尝试都重新生成 nonce 和 token（没有长期 key；每小时的请求量远低于 LEVEL_1 的上限 10 次）。
- `ERR_APP_INTEGRITY_PROVIDER_INVALID` → 重新 prepare 一次再请求；网络 / 429 → unavailable；provider 未就绪 → unavailable。
- 响应丢失 → 再次 create，接受重复 install（非目标）。

### 6.6 Persisted query（契约一定会变）

- `CreateInstall` 的 selection set 要从 `{ installId installToken }` 改成 `{ installId installToken attestationStatus }`，所以**operation 文本一定会变**：`apps/shared/src/api/graphql.ts` 的 query、`_API_ENTRIES`、codegen 产物，以及服务端 `graphql/persisted-queries/customer/customer.json` 都要同步更新。
- 新增 operation：`m_install_createAttestChallenge`、`m_install_recoverInstall`、`m_install_attestExisting`。
- **发布顺序**：
  1. 先合入服务端的 schema 和 allowlist；
  2. 再合入客户端的 query 和 codegen。

  已核实 allowlist 的机制（`TrustedDocumentProvider`）：服务端**按 reqName 取出 allowlist 里存的文本来执行，完全不读请求 body 里的 query**。所以：
  - 服务端只要把 `m_install_createInstall` 存的文本更新成包含 `attestationStatus` 的版本即可，**不需要 V2 名字**。
  - 旧客户端会多收到一个 `attestationStatus` 字段，按 JSON 解析会被忽略，没有影响。
  - 新客户端必须等服务端上线之后再发布，否则拿不到这个字段。这个顺序由发布顺序保证；另外客户端把字段缺失当作 30 处理，作为兜底。
  - 新增的三个 operation 必须先进服务端 allowlist，否则客户端请求会被 403000 拒绝。
- **契约测试**：客户端断言 `CreateInstall` 的 selection set 包含 `attestationStatus`；服务端断言 allowlist 里的文本能通过当前 schema 的校验。
### 6.7 存量 install 补证（`m_install_attestExisting`，用户决定：方案 A）

**为什么要补证**：`ensureInstall()` 只要本地有凭证就直接返回，所以下面两类 install 永远不会再走证明流程：
- flag 打开之前创建的；
- OBSERVE 下 attestationStatus=20/30 的。

补证让官方客户端能把已有 install 升级为已验证状态，从而能用上 recover，OBSERVE 的数据也更完整。

**定位（明确限制）**：一期的补证只是一种**迁移能力**，不负责收口存量 token。攻击者提前囤下来、永远不会去补证的旧 installToken，在一期仍然有效；createInstall 切 ENFORCE 也只能约束新 token。要让未验证的存量 token 真正失效，需要二期在 createAnonymous / login 时按 iid 查 attestation，或者引入 install token 的 epoch / cutoff，或者做 denylist（§8）。

**接口**

```graphql
input AttestExistingInput {
    proof: InstallProofInput!          # 与 createInstall 相同（1a 只支持 provider 110）
}
type AttestExistingResult {
    "10=VERIFIED_PERSISTED / 20=NOT_PERSISTED（proof INVALID）/ 30=NOT_EVALUATED（服务端 OFF 或未配置）"
    attestationStatus: Int!
}
extend type Mutation {
    "只接受 installToken（type=5、没有 actor、带 iid）。把 App Attest key 绑定到当前 install。限流：10/60s/IP + 3/install/天"
    m_install_attestExisting(input: AttestExistingInput!): AttestExistingResult!
}
```

**服务端**

```
1. 鉴权（严格只认 installToken，和 updateInstall 一致）：
     action.tokenType == TOKEN_TYPE_INSTALL && action.actorId == null && action.tokenInstallId != null
     不满足 → 401000（customer token、legacy x-install-id 都不行）
2. IP 短窗口：10/60s/IP
3. 服务端没启用（OFF / 未配置）→ 返回 30
4. verification = AttestGuard.verifyProof(...)          // 纯技术验证，不套用 mode
     INVALID     → 返回 20（与 mode 无关，ENFORCE 下也一样）
     UNAVAILABLE → 503002
5. 预查 key 的绑定（只读，用来决定走哪条路径；最终结果以第 8 步的事务内为准）
     当前 install 的 ACTIVE 绑定 → 走第 8 步的幂等路径（跳过第 6、7 步：不占新 key 额度，也不消费 challenge）
     BLOCKED / RETIRED          → 403002
     绑定在其他 install 上       → 409001
     没有绑定                   → 继续
6. 新 key 才检查：3 把新 key / install / UTC 日 → 超限返回 429002（验签后的日窗口，带到 UTC 零点的 Retry-After）
   （这个额度的含义是「每天最多绑定 3 把新的有效 key」。OFF、INVALID、UNAVAILABLE、幂等重试都不会占用它）
7. AttestGuard.consume(verification)：replay → 403001
8. 事务内（权威判定）：
     SELECT id FROM core_install WHERE project_id = ? AND id = ? FOR UPDATE
       没有这一行 → 404001 INSTALL_NOT_FOUND
     在锁内重新查 key 的绑定：
       当前 install 且 ACTIVE  → 10（幂等，不新增记录）
       BLOCKED / RETIRED      → 403002
       其他 install           → 409001
       没有绑定：
         统计这个 install 的 ACTIVE key 数量；已有 5 把时，把最早的一把置为 RETIRED
           （ORDER BY created_at ASC, id ASC LIMIT 1）
         插入新的 core_install_attestation（ACTIVE）→ 10
     subject 唯一约束冲突（另一个 install 并发绑定了同一把 key）→ 回滚，重新查绑定，再映射成 10 或 409001
```

- 锁 install 行（Jimmer 的 forUpdate 查询，Jimmer 表达不了就按 AGENTS.md 用 JdbcClient），保证"统计 → 退役 → 插入"这几步对同一个 install 是串行的，并发补证也不会突破 5 把 ACTIVE key 的上限。
- 同一把 key 被两个不同的 install 并发绑定时，仍然靠 subject 唯一约束兜底。
- 不修改 `core_install` 的 platform / storeType，也不重签 token。
- **已知限制**：BLOCKED 是 key 级别的，不是 install 级别的。同一个 install 生成一把新 key 后，仍然可以再补证（受 3 次/天和 5 把 ACTIVE key 的上限约束）。install 级别的封禁放到二期（§8）。

**客户端**

- 调用时必须用 `makeInstallGqlOpts()`（只发 installToken）。不能用默认的 `makeGqlOpts()`，因为它在有 customer session 时会优先发送 customer token。
- 触发条件：flag 开着、本地有 installToken，`attestState` 不是 UNSUPPORTED，**并且不满足「REGISTERED 且 `state.installId == 当前 installId`」**（REGISTERED 但 installId 对不上，说明本地 install 已经换过，需要重新补证），本次启动也没有因为 30 停止补证。
- 提交前检查：`targetInstallId === currentInstallCredentials.installId`（直接比 installStore 里的 installId，客户端不解析 JWT；installId 和 JWT iid 的一致性由服务端保证），对不上就废弃这次 pending proof（key 也一起废弃）。这样网络请求期间本地 install 被切换了，proof 也不会提交到错误的 installToken 上。清除 install 凭证时不需要清 `attestState`，installId 对不上自然会重新进入补证。满足时在 `initAttestation` 之后后台执行，每次启动最多一个补证 workflow，workflow 内部按 §6.8 的调度规则有限重试，不阻塞 UI，`ensureInstall` 照常直接返回已有凭证。
- 状态机复用 §6.4，把 `create(proof)` 换成 `attestExisting(proof)`。**outcome 映射和 createInstall 不同**：

  | 结果 | 转移 |
  |---|---|
  | 10 | → REGISTERED |
  | 20 | 这把 key 已经 attest 过，但服务端判定无效 → 废弃 key → EMPTY，下次启动再试 |
  | 30 | 服务端没有评估 → 废弃 key → EMPTY，本次启动不再重试，等下次启动或 flag / 配置变化 |
  | 403001 replay | 并发提交时失败的一方。**原 proof 最多再重发 1 次**（正常情况下服务端会走到「同 install 幂等返回 10」）。如果再次收到 403001（比如另一方 consume 之后事务失败了），转 RECOVER_PENDING，recover 返回 404 时废弃 key。**recover 拿回来的 installId 如果和当前的不一致，按 409001 处理：废弃 key，不切换 install** |
  | 409001 KEY_BOUND_TO_OTHER_INSTALL | **废弃 key，保留当前 installToken，绝不 recover 这把 key**（recover 会把客户端悄悄切换到别人的 install）→ EMPTY，下次用新 key 补证 |
  | 403002 KEY_BLOCKED | 废弃 key，不 recover → EMPTY |
  | 404001 INSTALL_NOT_FOUND | installToken 指向的 install 已经不存在。installToken 没有 exp，验证时也不查库，所以**它不会自然变成 401**，必须由客户端主动收敛（§6.8 协调器）：<br>① `clearInstallIfCurrent(capturedInstallId, capturedEpoch)`：只有当前 install 还是 A 时才清除，避免误清前台刚建好的 B<br>② `ensureInstall()` 创建新 install B<br>③ **有 customer session**：用 B 的 installToken 调一次 refresh，服务端会 `bind(B, customer)`（已核实 `AuthAggHandler.refresh` 252-292 行），返回 iid=B 的新 access token；customer 身份保持不变，refresh token 正常轮换<br>④ 对 B 重新进入补证（attestState 里的 installId 对不上，自然会重新补证） |
  | 429000 | 保持状态，按 Retry-After 重发同一份 proof |
  | 429002（每天新 key 额度用完） | 这把 key 已经 attest 过，但当天不可能再绑定了 → 废弃 key → EMPTY，等 Retry-After 之后再试 |
  | 503002 / 网络错误 | ambiguous，按原规则重试 |

- `ProofStep` 中的 `{ kind: 'attest_existing'; proof }` 由后台补证任务使用，与 `ensureInstall` 的 create / recover 路径互不影响。

### 6.8 客户端生命周期与协调

#### 状态的上下文绑定

`attestState` 不再是全局单条记录，而是**按 project + API 环境分别存放**：key 为 `antique.attestState.{projectId}.{apiEnv}`。每条记录带一个信封：

```ts
interface PersistedAttestState {
  schemaVersion: 1;
  projectId: string;
  apiEnv: 'local' | 'dev' | 'prod';
  appAttestEnvironment: 'development' | 'production';   // 与二进制里的 entitlement 一致
  appVersion: string;                                    // 用于 UNSUPPORTED 的重新检测
  state: AttestState;
}
```

- 加载时，如果信封里任一字段和当前运行上下文对不上（project、apiEnv、appAttestEnvironment、schemaVersion）：
  - 打日志 `attest.state_context_mismatch`；
  - **不向当前环境发送这份 proof**；
  - 当前环境按 EMPTY 处理。不同 apiEnv 的记录本来就分开存，正常情况下不会出现交叉。
- UNSUPPORTED 只对写入它的 `appVersion` 有效。App 升级后会重新检测一次，旧版本写下的 UNSUPPORTED 不会永久挡住新版本。
- **API 环境切换**（`setActiveEnv`，只有 dev 能用）：installStore 和 tokenStore 用的是固定的 SecureStore key，单靠"清内存再重启"做不到隔离，因为重启后又会读出旧环境的凭证。所以切换时**直接清掉持久化凭证**（ponytail：开发时切环境丢一次凭证可以接受；如果以后需要保留，就把存储按 `{projectId}.{apiEnv}` 分开）：
  ```
  await coordinator.pauseAndDrain()
  → 清 installStore、tokenStore（SecureStore）
  → 清 API client 的内存缓存（cachedInstall / session）、installEpoch++
  → setActiveEnv(next)
  → 提示重启
  ```
  attestState 本来就按环境分开存，所以这里不用清。

#### 协调器（create / backfill / clear 共用）

shared 的 `createServerApi` 内部维护一个 install 协调器，`ensureInstall`、补证 workflow、`clearInstall` / 重建都通过它：

```ts
let installEpoch = 0;                 // 每次清除或替换 install 时 +1
const mutex = createMutex();          // next / report / clear / rebuild 全部串行执行

async function clearInstallIfCurrent(expectedInstallId: string, expectedEpoch: number): Promise<boolean>
// 只有当前 installId === expected 且 epoch === expected 时才清除；清除后 epoch++，返回 true
```

- 补证 workflow 开始时记下 `{ installId, epoch }`。收到响应后，只有当前 installId 和 epoch 都没变，才允许修改 attestState 或清凭证；否则这次结果直接丢弃，打日志 `attest.backfill_stale`。
- 前台路径（createAnonymous / updateInstall 遇到 401 后清除重建）也走 `clearInstallIfCurrent`，不能直接操作 installStore。
- **硬规则：mutex 从不跨外部 IO 的 await 持有。** fetch、`fetchChallenge`、`attestKeyAsync` / `generateAssertionAsync`、`session.refresh()`、`ensureInstall()` 都必须在锁外调用。迟到的结果一律靠 `installEpoch` / `attemptId` 校验后再提交。锁只保护很短的临界区：
  ```ts
  const snap = await mutex.run(() => {          // 读状态、写入调用前的意图、记下 installId / epoch / attemptId
    ...; return snap;
  });
  const result = await nativeOrNetworkCall(snap); // 锁外
  await mutex.run(() => {                         // 检查 epoch / attemptId 是否还匹配，再按 CAS 方式提交
    if (stale(snap)) return discard();
    commit(result);
  });
  ```
- 404001 自动恢复也遵守这条规则：`clearInstallIfCurrent` 在锁内；创建 B 和 `session.refresh()` 在锁外。`raw.refresh` 内部的 `ensureInstall` 会直接命中已经建好的 B（走它自己的 single-flight），不会去等外层的锁，所以不会自锁。
- `InstallProofProvider.next/report` 对 attestState 的读写都在上面这种短临界区内完成，create 和 backfill 不会同时修改 attestState。
- 接口显式传入快照，provider 自己不读 installStore：
  ```ts
  type InstallSnapshot = { installId: string; epoch: number } | null;
  next(ctx: { operation: 'bootstrap' | 'backfill'; install: InstallSnapshot; fetchChallenge: () => Promise<ChallengeResult> }): Promise<ProofStep>
  ```
  `operation='bootstrap'` 且 `install == null` 时，provider 按 attestState 决定下一步：
  - `REGISTERED`（凭证已经丢了）→ **recover，不 create**。这种情况确实会发生：服务端已经创建成功、客户端写进了内存缓存，但 SecureStore 只做 best-effort 持久化，进程在写入前被杀掉。
  - `ATTESTED_PENDING` → 在有效期内重发原来的 proof。
  - 其他状态按 §6.4 处理。

  `operation='backfill'` 时 `install` 一定不为空，`targetInstallId` 就是 `install.installId`。
- **协调器是唯一可以操作 install 凭证的地方**。下面这些现有的直接清除路径，都要改成调用协调器：
  - `apps/shared/src/api/client.ts` 内部的 `clearInstall()`（401 重建）→ `coordinator.clearInstallIfCurrent`；
  - `apps/antique/src/features/install/clearCredentials.ts`（Dev「Clear Install & Session」）→ `coordinator.resetAll()`：清 SecureStore、attestState、fixture、定时器、内存和 epoch；
  - `apps/antique/src/app/dev-settings.tsx` 切换 API 环境 → `coordinator.switchEnv(next)`（流程见上文：pauseAndDrain → 清持久化凭证 → setActiveEnv → **要求重启**，重启之前不再发起任何证明请求）。

  app 层禁止直接读写 installStore。

#### 生命周期

| 操作 | installStore | customer session | attestState |
|---|---|---|---|
| Logout | 保留 | 清除 | 保留 |
| 删除账号（`wipeLocalUserData`） | 保留 | 清除 | **保留**（见下面的存储规则），删除账号不会触发生成新 key |
| 服务端判定 install 不存在（404001） | 重建 | 保留身份，用新 install refresh 并重新 bind | 按新 install 重新补证 |
| Dev「Clear Install & Session」（模拟全新设备） | 清除 | 清除 | **清除所有** `antique.attestState.*` 和 `antique.dev.attestFixture.*`，同时清掉定时器、协调器内存和 epoch |
| 服务端返回 401，清除 install | 清除并重建 | 按现有规则处理 | **不删除**：installId 对不上，会自然进入对新 install 的补证 |
| App 卸载 | iOS Keychain 可能保留 | 可能保留 | AsyncStorage 被清空 → 重新生成 key；如果 Keychain 里残留的 install 还在，会通过补证绑定新 key |
| API 环境切换（dev） | **清除**（见上） | **清除** | 保留（本来就按环境分开存） |

`wipeLocalUserData` 的保留规则从精确匹配改成「精确 + 前缀」：

```ts
const DEVICE_KEEP_EXACT = ['antique.freeScanCount'];
const DEVICE_KEEP_PREFIXES = ['antique.attestState.'];
const keep = (key: string) =>
  DEVICE_KEEP_EXACT.includes(key) || DEVICE_KEEP_PREFIXES.some((p) => key.startsWith(p));
```

fixture 的 key（`antique.dev.attestFixture.*`）**不保留**，删除账号时会一起删掉。

#### 状态解析与迁移

- 每条记录都会校验：`schemaVersion` 已知、stage 已知、这个 stage 需要的字段齐全（比如 KEY_READY 及之后都要有 keyId；补证路径上的 pending 状态要有 targetInstallId），并且长度不超限（keyId 64、challenge 128、attestationObject 16 KB）。
- 校验失败时**不一律当作 EMPTY 处理**：
  - 能解析出 keyId，而且 stage 可能已经 attest 过（ATTESTING 及之后）→ 保守地进入 `RECOVER_PENDING { keyId }`。recover 返回 404 时再废弃，避免白白多生成一把 key。
  - 解析不出 keyId → EMPTY，并打日志 `attest.state_corrupt`（带 stage 和原因，如果有 keyId 则带它的 hash），作为可能的孤儿 key 记录下来。
- 将来升级 schemaVersion 时提供显式迁移函数。遇到不认识的更高版本，按「校验失败」处理。

#### 统一调度

```
单个 bootstrap cycle：
  503002 / 网络错误：retryTransient 最多快速尝试 3 次
  403 / 404：按状态机最多立即推进 2 步
cycle 失败：
  进入长退避 30s → 2m → 10m（封顶），同时监听网络恢复；任一触发都由 single-flight 合并成一次 cycle
  有 Retry-After 时，优先按 Retry-After 等待，不按通用退避
```

- 任意时刻最多只有一个定时器。`RootLayout` 卸载时，清理定时器和网络监听。
- App 在后台时不启动新的 cycle；回到前台可以提前触发一次。
- UNSUPPORTED 和 status=30 不进入定时重试。
- 「每次启动最多一个补证 workflow」：workflow 内部按上面的规则有限重试，和 create 的 cycle 共用这一个调度器。

#### 前台错误体验

后台的 bootstrap 和补证都是静默的。只有当用户主动发起的操作（scan、login 等）因为拿不到 install 而被挡住时，才展示错误。API client 暴露归一化的 `InstallBootstrapErrorKind`：

| kind | 来源 | UI |
|---|---|---|
| `UNAVAILABLE` | 503002 / 网络 | 暂时无法验证，请稍后重试 |
| `RATE_LIMITED_SHORT` | 429000 | 短暂等待（按 Retry-After） |
| `RATE_LIMITED_DAILY` | 429002 | 今日注册次数过多，显示下次可用时间 |
| `INTEGRITY_FAILED` | 403001（createInstall，ENFORCE） | App 完整性验证失败，提示使用官方版本 / 重新安装 |
| `DEVICE_UNSUPPORTED` | UNSUPPORTED + ENFORCE 下 403001 | 当前设备不受支持，并给出支持入口 |
| （不展示） | 409001 / 403002 / 补证的任何错误 | 后台记录日志，生成新 key，不切换 install，也不打扰用户 |

文案走 i18n（`scripts/check-i18n-keys.cjs` 会校验 key 是否齐全）。

#### 客户端日志脱敏

`apps/shared/src/api/graphql.ts` 在 `__DEV__` 下会把 variables 和 response 完整打印出来，而 `SENSITIVE_LOG_KEYS` 目前只包含 `accessToken` / `refreshToken` / `credential` / `deviceSecret`。**连现有的 `installToken` 都没有覆盖**，这是一个已经存在的泄露点，这次顺带修复。

- `SENSITIVE_LOG_KEYS` 增加 `installToken`、`assertion`、`attestationObject`、`integrityToken`、`challenge`、`nonce`。
- `proof` 整个子树按不透明数据处理：日志里只保留 `provider`、各字段的长度，以及 `keyId` 的 SHA-256 前 8 位。
- `redactForLog` 补单测：上面这些字段不能以原文出现在输出里，嵌套在 `input.proof.appAttest.*` 里的也一样。
- 状态机日志（`attest.*`）同样只记录 keyId 的 hash。

#### fixture 与生产完全隔离

- fixture 状态用独立的 key `antique.dev.attestFixture.{projectId}.{apiEnv}`，不读也不写生产的 `attestState`。
- 生成 fixture 前先 `await coordinator.pauseAndDrain()`：禁止启动新的 cycle，等当前 cycle 退出（或者它的 attempt 作废）之后，再调用 DCAppAttestService；放在 `try / finally` 里调用 `resume()`，保证一定会恢复。「Generate New Key」不会触发后台补证。
- 测试要覆盖：fixture 和生产协调器并发写入时互不影响。
---

## 7. 测试

服务端（`./gradlew :core-api:test`、`:core-job:test`）：
- 字节契约：固定 challengeStr → clientDataHash / expectedNonce；Android context → requestHash；recover clientData。客户端使用同一组向量。
- `AppAttestVerifier`：真机录制 attestation / assertion fixture；篡改 rpId、错 challenge、counter≠0、assertion counter 回退均失败。
- `AttestChallengeCodec`（注入固定 `Clock`，不依赖真实时间）：签发后能通过校验；篡改任意字节 → 失败；换成别的 projectId → 失败；超过 300s → 过期；用 previous 密钥签发的 challenge 仍可校验。
- `AttestReplayGuard`：第一次 FIRST，第二次 REPLAY；Redis 异常 → DEGRADED，放行并打节流后的 ERROR；**Redis 故障期间重放同一份 attestation → 被判为 key_reused，不会多出 install**。
- `PlayIntegrityVerifier`：各校验项失败 → INVALID；5xx / 超时 → UNAVAILABLE；同 token 二次 → replay。
- `AttestGuard` 判定矩阵：
  - 维度：全局开关 × mode × {VALID, INVALID, Redis 故障（放行 + ERROR）, Google UNAVAILABLE（1b：5xx / 429 / 超时）, 无 proof 未声明, 无 proof + proofStatus=UNAVAILABLE}。
  - **ENFORCE 下 Google UNAVAILABLE 返回 503002；Redis 故障时，只要其余校验通过就放行，并打 `attest.redis_degraded`。**
  - 声明弱 provider 的垃圾 proof，在 ENFORCE 下返回 403001。
  - mode=ENFORCE 但平台配置缺失，三个接口都返回 503002，并打出 `attest.config_invalid` 日志。
  - 未知 provider、provider 与子对象不匹配、proof 和 proofStatus 同时出现、proofStatus 取非法值，都返回 400000。
- 限流：
  - createInstall 日窗口：
    1. 第 1001 个 VALID 请求被 attested 计数器拒绝；
    2. 第 101 个未验证请求被 unverified 计数器拒绝；
    3. 入口短窗口：同一 IP 在 60 秒内的第 101 个请求被拒绝（不管有没有带 proof）；
    4. OBSERVE 下 INVALID 请求计入 unverified；
    5. ENFORCE 下 INVALID 请求在 Guard 阶段就被拒绝，不会创建日窗口 key；
    6. 返回 503002 的请求不会创建日窗口 key；
    7. unverified 额度用完后，VALID 请求仍然走自己独立的 attested 计数器；
    8. attested 额度用完，不影响 unverified 计数器；
    9. key_reused 发生在事务阶段时，日额度已经消耗，不退；
    10. Redis 故障时放行，并打出节流后的 `ratelimit.degraded` 日志；
    11. 跨过 UTC 零点后切换到新的计数器 key；
    12. 修改配置后要重启才生效（配置在启动时绑定）。
  - 伪造 proofStatus=UNAVAILABLE 的请求，仍然会被短窗口拦住。
  - 下游的顺序和策略：
    1. 某个 install 达到日上限后继续请求，IP 日计数器不再增加；
    2. 某个 install 达到分钟上限后继续请求，IP 分钟计数器不再增加；
    3. IP 层拒绝时，install 计数器这次已经加 1（明确的语义，不退）；
    4. 没有 iid 的 legacy 请求使用 legacy 计数器和旧的严格阈值（anonymous 10/60s、scan 5/min + 500/天、DR 3/min + 300/天），不占用大额 IP 计数器；
    5. 关闭 legacy fallback 后，没有 iid 的请求在业务之前被拒（401000）；
    6. 同一个 IP 在不同 project 下使用不同的计数器；
    7. Project A 把额度耗尽，不影响 Project B；
    8. 短窗口遇到 Redis 故障也返回 Degraded，日志同样受节流；
    9. 同一个 iid 换了 IP，install 层额度保持不变。
  - 429 的两个位置：入口短窗口返回 429000，challenge 没有被消费，带 Retry-After；日窗口返回 429002，challenge 也没有被消费，带上到 UTC 零点的 Retry-After。
  - 并发提交同一份 proof：只有一个成功，另一个得到 403001(replay)，两次都扣了日额度。
  - 验签信号量耗尽：OBSERVE 放行，ENFORCE 返回 503002。
  - 下游两层（数值都从配置读取）：
    - createAnonymous：同一个 install 第 6 次被拒；同一个 IP 60 秒内第 101 次、一天内第 1001 次被拒；
    - scan / DeepResearch：同一个 install 每分钟第 6 次、每天第 101 次被拒；同一个 IP 每分钟第 101 次、每天第 1001 次被拒；scan 和 DR 分别计数；
    - install 层只认 `tokenInstallId`：伪造 `x-install-id`（走 legacy fallback）时不会按那个 iid 计数，只受 IP 层限制；没有 iid 的旧 token 只受 IP 层限制；
    - 同一个 install 下的多个匿名 customer 共用一份 install 额度；换 IP 不影响 install 计数。
  - `attest.config_invalid` 日志：同一个 projectId + configHash 首次打 ERROR，之后每分钟最多一条；配置修复后打一条 recovered。
  - 限流 Redis 降级：放行并打 `ratelimit.degraded` ERROR；同一个 subject 前缀每分钟最多一条；恢复后打 recovered。
- storeType：缺省时存 NULL；取 10 / 20 时正常写入；其它值返回 400000；updateInstall 和 recover 都不修改它；provider 110 VALID 但 storeType≠10 时，只打 `store_mismatch` 日志、不拒绝。
- `CreateInstallResult.attestationStatus`：VALID 并绑定成功 → 10；OBSERVE 下 INVALID → 20；没带 proof、mode=OFF、全局开关关闭 → 30；recover → 10。
- `createAttestChallenge`：全局开关关闭、project 未配置、mode=OFF、ios 未配置时，返回 `enabled=false`、`challenge=null`。
- `AppAttestTrustAnchors`：资源指纹不一致时启动失败；用 development fixture 去验 production verifier 时失败。
- `sign_count`：首次 attestation 落库为 0，之后第一次 recover（counter=1）成功。
- `attestExisting`（补充）：
  - **第一次补证成功但响应丢失 → 重发原 proof → 返回 10，不新增第二条记录**（在消费 challenge 之前先查绑定）；
  - **mode=ENFORCE 时 INVALID → HTTP 200，attestationStatus=20，不写入记录**；
  - customer token 调用 → 401000；只有 installToken 能调用；
  - key 绑在别的 install 上 → 409001（不是 403001）；key 是 BLOCKED 或 RETIRED → 403002；同一个 install 下的 BLOCKED key 不走幂等路径；
  - 第 6 把 key → 最早的一把（created_at, id 升序）被置为 RETIRED，被 RETIRED 的 key 不能再 recover；
  - **并发**：已有 5 把 ACTIVE key，两把不同的新 key 并发补证 → 两个请求串行完成，最终仍然只有 5 把 ACTIVE，恰好 2 把旧 key 变成 RETIRED；
  - 两个不同的 install 并发绑定同一把 key：一个成功，另一个得到 409001；
  - 预查时还是 ACTIVE，事务内已经被 RETIRED 或 BLOCKED → 返回 403002（以事务内的结果为准）；
  - **新 key 额度**：第 4 把**不同、有效、尚未绑定**的 key 返回 429002；OFF（30）、INVALID（20）、UNAVAILABLE、同 key 幂等重试都不占这个额度；
  - install 行不存在 → 404001（不是 404000）；
  - 服务端 OFF → 30。
- `attestExisting`：
  - 没有 tokenInstallId（包括伪造 `x-install-id` 走 legacy 的情况）→ 401000；
  - VALID → 10，新增一条 attestation 记录；
  - INVALID → 20，不报错；
  - 补证之后，用这把 key 可以 recover 成功。
- `InstallAggHandler`：VALID 落库并派生 platform；key_reused 在 OBSERVE 和 ENFORCE 下都返回 403001 且不建 install；recover 的条件更新（并发 / BLOCKED → 403001）。
- core-job：`next_refresh_at` 选取、成功写回、429 退避；evidence 90 天清理。

客户端：
- 状态机（逐条覆盖 §6.4 的转移表）：
  - 写状态失败时，不调用 generateKey / attestKey。
  - 遇到 `SERVER_UNAVAILABLE`，用同一把 key 重试。
  - `create_unverified`（attestationStatus=20/30）时**不进入 REGISTERED**，并废弃 key。
  - 遇到 `FEATURE_UNSUPPORTED`，或者 `isSupported=false`：写入 UNSUPPORTED，之后再也不尝试 attest。
  - challenge 返回 `enabled=false`：不生成 key。
  - 补证：flag 开、有 installToken、状态不是 REGISTERED 时，在后台触发一次；按 attestationStatus 推进状态；失败不影响 ensureInstall 返回已有凭证。
  - 补证请求用的是 installToken（`makeInstallGqlOpts`），即使已经有 customer session 也一样。
  - 409001 → 废弃 key，installToken 不变，**不发起 recover**；403002 → 废弃 key；30 → 废弃 key，本次启动不再重试。
  - 404001：compare-and-clear（只有当前还是 A 时才清）→ 创建 B → 有 customer session 时用 B 的 installToken refresh，服务端 bind(B)，新 access token 的 iid=B，customerId 不变 → 对 B 补证。
  - 403001 replay：原 proof 只重发 1 次；第二次还是 403001 → RECOVER_PENDING；recover 拿回来的 installId 和当前的不同 → 按 409001 处理，不切换 install。
  - §6.8 生命周期 / 协调：
    - 状态信封任一字段不匹配（project / apiEnv / appAttestEnvironment / schemaVersion）→ 打 context_mismatch 日志，不发送 proof；
    - 状态记录损坏：JSON 格式错误、未知 schemaVersion、未知 stage、stage 缺 keyId、pending 缺 targetInstallId → 有 keyId 时转 RECOVER_PENDING，没有时 EMPTY 并打 state_corrupt 日志；
    - 竞态：补证读到 A，前台此时清掉 A 并建了 B，补证随后收到 A 的 404001 → `clearInstallIfCurrent` 返回 false，B 不受影响；
    - 删除账号保留 attestState；Dev「全新设备」会清掉 attestState 和 fixture；
    - UNSUPPORTED 在 appVersion 变化后重新检测；
    - 调度：同一时刻只有一个定时器；网络恢复和定时器同时触发时只跑一个 cycle；后台不启动 cycle；有 Retry-After 时优先按它等待；
    - fixture：生产 cycle 还在跑时触发生成 fixture，fixture 会先等它结束（pauseAndDrain），两者不会同时调用 DCAppAttestService；生成失败时，生产协调器仍然能恢复（finally）。
    - **环境切换**：dev 下已经有 install 和 session → 切换到 prod → 重启 → 不会读到 dev 的凭证（SecureStore 已经清掉了）。
    - **真实存储 key**：删除账号之后，`antique.attestState.antique.prod` 还在，`antique.dev.attestFixture.*` 已被删除；Dev「全新设备」之后两者都被删除；401 清除 install 时 attestState 保留。
    - **404 自动恢复（集成测试）**：当前是 A、有 customer session → backfill(A) 返回 404001 → `clearInstallIfCurrent(A, epoch)` 返回 true → 创建 B → 用 B 的 installToken refresh → 新 access token 的 iid=B、customerId 不变 → backfill(B)；整个过程在超时时间内完成，没有死锁。
    - **404 并发变体**：backfill(A) 正在进行时，前台已经创建了 B → A 返回 404001 → `clearInstallIfCurrent(A, oldEpoch)` 返回 false → 不清除 B，也不会重复创建。
    - **mutex**：在锁内调用外部 IO 时，测试里用一个 fake mutex 直接抛错（防止回归）。
    - **凭证丢失**：attestState=REGISTERED，但 installStore 为空 → 走 recover，**不 create**。
    - backfill 对 A 的迟到结果，不能清除或覆盖已经重建好的 B。
    - Dev「Clear Install & Session」会同时清掉协调器内存、定时器和 attestState。
    - 快照里的 installId 和 targetInstallId 不一致时，不发送 proof。
    - **启动顺序**：本地已经有 `apiEnv=dev` 覆盖时，第一次 challenge / create 请求发到 dev；`initAttestation` 被传入不同的 ctx 时抛错。
    - **日志脱敏**：`redactForLog` 对 installToken、attestationObject、assertion、integrityToken、challenge、nonce 以及嵌套的 proof 子树都会脱敏。
    - **persisted query 契约**：`CreateInstall` 的 selection set 包含 `attestationStatus`；服务端 allowlist 里的文本能通过 schema 校验。
  - REGISTERED 但 installId 和当前 install 不一致 → 重新进入补证；pending 状态的 targetInstallId 和当前 iid 不一致 → 废弃这次 pending，不提交。
  - 429000 时保持原状态，重发同一份 proof；429002 时废弃 key。
  - attest / assertion 超时：attempt 作废，迟到的结果不改写状态。
  - ATTESTED_PENDING 在 challenge 有效期内重发同一份 proof，过期后转 RECOVER_PENDING。
  - **ATTESTED_PENDING、challenge 尚未过期、收到 replay_or_reused 时，下一次必须走 recover，绝不能再 create。**
  - ATTESTING 状态下重启，转 RECOVER_PENDING。
  - recover 返回 404 时废弃 key，且之后不再对它 attest。
  - report 写存储失败时，本进程按内存状态继续执行，并打出 `pending_state_persist_failed` 日志。
  - 单次 bootstrap 最多新建 1 把 key。
- `client.ts`：`unavailable` 时带 `proofStatus=20`；403001 / 404000 不在 retryTransient 内重试；503002 有限重试；fetchChallenge 返回 503 不会被降级成 no-proof。
- `ensureInstallWhenOnline`：initAttestation 完成之前不发请求；失败后按 §6.8 调度。

---

## 8. 分期与灰度

### 一期 1a（iOS，本次实施）

服务端：
- challenge、createInstall（appAttest）、recoverInstall、attestExisting（存量补证）；
- `core_install_attestation` 表、结构化日志、§4.6 限流；
- core-job 的 fraud metric 刷新和 evidence 清理。

客户端：iOS 状态机，以及 flag、initAttestation、协调器、proofStatus、503002 重试、日志脱敏。

mode 设为 OBSERVE。`app_attest_config` 只配 `ios`。Android 客户端在这个阶段走 `no-proof`。

### 一期 1b（Android，Android 客户端发布前实施）

- 服务端：PlayIntegrityVerifier、`android` 配置。
- 客户端：prepare 和 Android 证明分支。
- 协议按 §3.2，本文已经定稿。
- 如果发布时 iOS 已经是 ENFORCE，要遵守 §4.1 的推论：Android 首个版本就要带上证明，并在发布前完成服务端配置。

一期数据注意：客户端状态机覆盖率不足时，响应丢失仍可能产生额外的 key，从而抬高自家的 fraud metric，**不能直接用来制定 fraud metric 阈值**。分析时要剔除有 `pending_state_persist_failed` 记录的 install，以及 recover 返回 404 后废弃 key 的那些样本。

### 切 ENFORCE

满足 §4.5，且（草案）：每个已发布平台的支持版本 VALID ≥ 99%、UNAVAILABLE（含 client_unavailable）< 0.1%、`reason` 没有异常集中，按 av 分组持续 7 天。当前只有 iOS 一个平台。

### 二期（另起规格）

| 平台 | 信号 | 草案动作 |
|---|---|---|
| Android | deviceActivityLevel LEVEL_3 / LEVEL_4 | 限制 / 拒绝 |
| Android | licensing UNLICENSED | 视分布 |
| Android | Device Recall 滥用 bit | 拒绝；滥用后写 bit |
| iOS | fraud_metric 持续偏高 | key 置 BLOCKED（一期语义只是禁止 recover / 新绑定） |

**真正的 install 封禁**（BLOCKED 要对已签发的 installToken 也生效）需要从以下方案中选一个：
- `core_install.blocked_at`，在敏感入口（createAnonymous / login / createScan）查询，加缓存；
- Redis denylist（按 installId）；
- installToken 改为有限 TTL 并支持轮换；
- token 加 epoch，服务端校验当前 epoch。

另：敏感业务请求的 assertion（proof-of-possession）。

### 以后

- 存量未验证 installToken 的收口：可以在 createAnonymous / login 时按 iid 查 attestation，或者引入 install token 的 epoch / cutoff，或者做服务端 denylist。install 级别的封禁也一起在这里考虑。
- 已验证设备的 IP 层放宽：本期 install 层已经存在，而且不区分是否已验证。如果以后出现 CGNAT 误伤（共用 IP 的真实用户撞到 IP 层），可以让已验证的 install 跳过 IP 聚合，只保留一道高位硬上限。"已验证"用 customer token 的 `att` claim 表达：每次签发时查库，依次经过 VerifiedToken → Actor → ActionContext 传递；`signAccess` 加不变量 `require(!attested || installId != null)`。这部分要另起规格，本期不保留任何代码入口。
- Android 长期 key：`@expo/app-integrity` 的 `generateHardwareAttestedKeyAsync`，可提供与 iOS 类似的 key 身份与找回。
- 微信小程序：新增 provider，证明为 `wx.login` code → `code2session`，subject = openid；与现有社交登录（IDP / AuthIdentity）的边界接入时再定。
- Web：没有平台证明可用；如需人机验证（如 Turnstile），作为独立的 step-up 机制另行设计，不属于 provider。

---

## 9. 上线步骤（1a，iOS）

1. 服务端合入（全局开关关、无 project 配置）→ 现网零影响。
2. Apple：开启 App Attest capability，生成 DeviceCheck 私钥。
3. 写入 project 的 `app_attest_config`（mode = OBSERVE，只配 `ios`）。
4. 客户端发新 runtime（flag 默认关）。
5. `APP_ATTEST_GLOBAL_ENABLED=true`，OTA 打开 flag。
6. 观察日志与 SQL → iOS 达标后切 ENFORCE；同时启动二期策略规格。

**上线前必须通过的检查（不可跳过）**：
- **Apple Developer**：App ID 已开启 App Attest capability，development 和 distribution 两套 provisioning profile 都已重新生成，并在构建中实际用上。只在 `ios.entitlements` 里手写这一项，不能保证签名 profile 会接受它。
- **最终二进制**：对 Archive 出来的 `.app` 执行 `codesign -d --entitlements :- <App>.app`，确认 `com.apple.developer.devicecheck.appattest-environment` 存在，并且值正确。
- **环境映射**：开发构建对应服务端 `ios.env=development`；TestFlight 和 App Store 构建一律走 Apple 的 production 环境，对应 `ios.env=production`。preview 构建如果经过 TestFlight 分发，同样按 production 处理。`appAttestEnvironment` 在构建时注入，记录到状态信封里。
- **TestFlight 真机冒烟**：在 TestFlight 构建上，用 production verifier 完整跑通一次 create 和一次 recover（包括删除本地凭证后再 recover）。只有 development fixture 通过是不够的。
- **平台发布清单**：见 §4.5 第 4 条。

**新平台发布检查清单**（Android 等）：mode 为 ENFORCE 时，新平台客户端必须带上证明，并在发布前配置好服务端对应的子对象；否则发布期间先把 mode 切回 OBSERVE（§4.1）。

---

## 10. 实现前需核实

1a（iOS）：
1. ✅ 源码已核实（`@expo/app-integrity@57.0.2`，对应 SDK 57，peer `expo 57.0.22`；`IntegrityModule.swift`）：
   - `attestKeyAsync` 和 `generateAssertionAsync` 内部都执行 `clientDataHash = SHA256(Data(challenge.utf8))`，返回值都是 `base64EncodedString()`，与 §3.1 / §3.3 的字节契约一致；
   - **没有 config plugin**（包内无 `app.plugin.js`），entitlement 需要在 `app.config.js` 里手动配置；
   - iOS 错误码共 6 个：`ERR_APP_INTEGRITY_FEATURE_UNSUPPORTED` / `INVALID_INPUT` / `INVALID_KEY` / `SERVER_UNAVAILABLE` / `SYSTEM_FAILURE` / `UNKNOWN`，分别对应 DCError 的各个 code；
   - 实现时固定版本 57.0.2，按 antique 仓库约定安装：`pnpm --filter @ifmix/antique exec expo install @expo/app-integrity@57.0.2`（不要用 `pnpm add` 或 `npx expo install`，避免装到错误的 workspace）。真机端到端验证仍放在第 7 项。
2. App Attest entitlement 在 TestFlight / App Store 构建中是否被忽略（一律走 production）。这一项由 §9 的「TestFlight 真机冒烟」和 codesign 检查作为发布门槛来最终确认。
3. ✅ 已通过（2026-10-04 smoke test）：`com.webauthn4j:webauthn4j-appattest:0.30.1.RELEASE` 在本项目的 Gradle 9.6.1 + Corretto 25.0.4 下可以解析、编译、类加载。它会引入 Jackson 2（`jackson-databind` / `jackson-dataformat-cbor` 2.21.4），和 Spring 的 Jackson 3（`tools.jackson` 3.1.4）包名不同，可以在同一进程共存。实现时固定使用这个版本。**还没有验证的**：用真实 attestation / assertion 做解析和密码学校验，需要真机 fixture，见第 7 项。
4. Apple attestation data 端点、receipt 字段（not-before / 过期 / 下次刷新）与频率限制。
5. ~~Redis ≥ 6.2（GETDEL）~~：已不需要。challenge 改成无状态之后只用 `SET NX EX`，所有 Redis 版本都支持。
6. persisted query：createInstall operation 是否需要更新。
7. **真机 fixture 端到端**（1a 剩下风险最大的一项），方案见 §10.1。

### 10.1 真机 fixture smoke test

**客户端采样工具**（`apps/antique`，单独分支）

- **严格限定 dev 使用**：`dev-settings` 在正式包里也能进入（`support.tsx` 标题连点 5 次可进，`_layout.tsx` 始终注册了这个路由），所以不能只靠"按钮放在 dev-settings 里"来保证。
  - 渲染：`{__DEV__ && <AppAttestFixtureButton />}`
  - 采样函数里再拦一次：`!__DEV__` 或 `getActiveEnv() === 'prod'` 时直接抛错。
- **遵守 key 生命周期**：
  - 执行期间按钮置灰（single-flight）。
  - 已有 fixture 状态时，再点按钮只重新导出，不再 attest。
  - `SERVER_UNAVAILABLE` 时用同一把 key 重试。
  - 需要新 key 时，走单独的"Generate New Key"按钮，并弹确认框提示"会增加 fraud metric"。
  - 采样状态单独存一条 AsyncStorage 记录，和生产状态机的 `attestState` 分开。
- **使用生产协议，但不碰任何 secret**：预先生成两个固定的 challenge 字符串（格式和 §3.1 一致，用一个**只给 fixture 用、不属于任何环境**的测试 key 离线算出来）。客户端只内置这两个字符串，不内置任何 secret；fixture JSON 里也**没有 secret 字段**。HMAC codec 的正确性由服务端单测单独保证（§7），不靠真机 fixture 反推。
  - attestation：`attestKeyAsync(keyId, attestationChallenge)`
  - assertion：`clientData = "ifmix-install-recover-v1\n" + projectId + "\n" + assertionChallenge`
- **导出版本化 JSON**：
  ```json
  {
    "schemaVersion": 1,
    "capturedAt": "...",
    "environment": "development",
    "teamId": "...", "bundleId": "...", "projectId": "antique",
    "attestationChallenge": "...", "assertionClientData": "...",
    "keyId": "<base64>", "attestationObject": "<base64>", "assertion": "<base64>"
  }
  ```
- **传输方式**：
  - 用项目已有的 `expo-sharing` 分享 JSON 文件（AirDrop 到 Mac）。
  - 不粘贴到聊天，不提交到 Git。fixture 里有 Apple 证书链和 receipt，属于和开发设备关联的匿名证明。
  - 剪贴板只作兜底：使用时弹警告，60 秒后清空。

**服务端验证测试**（`core-api`）

- 用 `@Tag("app-attest-fixture")` 标记，通过 `APP_ATTEST_FIXTURE=/abs/path/fixture.json ./gradlew :core-api:test --tests '*AppAttestFixtureTest'` 运行。没有设置环境变量时明确 skip；显式运行时必须通过。
- 不进默认 CI：WebAuthn4J 校验证书路径时用的是当前时间，真实证书和 receipt 过一段时间会过期，fixture 会随之失效。默认 CI 只跑不依赖时间的单测：字节公式、错误 challenge、rpId、AAGUID、counter。
- WebAuthn4J 0.30.1 的配置要求：
  - `DeviceCheckAttestationManager` + `DCAttestationRequest(keyId, attestationObject, clientDataHash)` + `DCServerProperty(teamId, bundleId, challenge)`；
  - **信任锚**：从 Apple Private PKI 下载并固定 App Attestation Root CA，放入测试的 TrustAnchorRepository，使用 `DefaultCertPathTrustworthinessVerifier`。不能用 Null verifier，否则测的只是 CBOR 格式，没有验证信任链；
  - development fixture 要把 `DCAttestationDataVerifier.production = false`。
- 先从 attestation 里提取 P-256 公钥、credentialId、初始 signCount=0 和 receipt，用这些构造 `DCAppleDevice`，再验证 assertion：签名、rpIdHash、clientDataHash、counter > 0。
- fixture 测试**不经过** `AttestChallengeCodec` 的时效校验：固定 challenge 里的 issuedAt 是固定的，5 分钟后就会过期。fixture 测试直接拿记录下来的 challenge 字符串去验 WebAuthn4J 的 nonce、证书和 assertion。codec 的 MAC 校验、project 绑定、过期和 previous key，由注入固定 `Clock` 的单测覆盖。
- **重放负例要模拟服务端持久化 counter**：WebAuthn4J manager 不会替你持久化 counter。正确步骤：第一次验证通过后取出 newCounter；构造一个 counter = newCounter 的 `DCAppleDevice`；再用同一份 assertion 验证，此时预期失败。只用同一个初始 device 验两次是不够的，两次都可能通过。
- 负例：
  - 修改 attestation challenge；
  - 修改 assertion clientData；
  - 修改 teamId 或 bundleId；
  - 用 production 模式的 verifier 验证 development fixture；
  - 篡改 assertion 中任意一个字节；
  - 按上一条的方法，在 counter 已推进后重放同一份 assertion。

以上负例都必须失败。

1b（Android）：
8. Play Integrity decode 响应的字段路径（recentDeviceActivity / deviceRecall 的开启方式）。

---

## 11. 文档同步清单（实现时一并修改）

以下文档描述的是线上契约或现状，要和代码在同一个改动里更新，不能提前改：

| 文档 | 需要更新的内容 |
|---|---|
| `core-api/src/main/resources/schema/customer/install.graphqls` | createInstall 的描述改为新的限流规则（入口 100/60s；未验证 100/天、VALID 1000/天，按 IP）；新增 `proof` / `proofStatus` / `storeType` / `attestationStatus` 字段，以及 createAttestChallenge / recoverInstall / attestExisting |
| `core-api/src/main/resources/schema/customer/customer.graphqls` | createAnonymous 的描述改为：每 install 5 次/天；每 IP 100 次/60s、1000 次/天 |
| `docs/design/install-tracking.md` | createInstall 的限流说明；新增 attestation 一节，内容引用本规格 |
| `docs/DATABASE.md` | 新表 `core_install_attestation`；`core_install.store_type`；`core_project_server_config.app_attest_config` |
| `docs/release.md` | 未发布变更：attestation（默认关）、限流阈值调整、新 env（`APP_ATTEST_GLOBAL_ENABLED`、`APP_ATTEST_CHALLENGE_SECRET`） |
| `antique/docs/install-tracking-frontend-api.md` | 限流说明；proof / proofStatus / storeType 字段；recover 流程；403001 / 503002 错误码 |
| `ifmix_server/.../persisted-queries/customer/customer.json` + `antique/apps/shared/src/api/graphql.ts` | CreateInstall 的 selection set 加上 `attestationStatus`；新增三个 operation；按 §6.6 的发布顺序合入 |
| `antique/apps/shared/src/api/graphql.ts` 里的 `SENSITIVE_LOG_KEYS` | 加上 installToken（这是已经存在的漏洞）以及证明材料相关的字段（§6.8） |
| `antique/apps/shared/src/api/codes.ts` 注释 | 403001 / 403002 / 404001 / 409001 / 429002 / 503002 的含义 |
| `docs/design/install-tracking.md`、`antique/docs/idempotency-frontend-api.md` | **已更正（2026-10-04）**：原来写「refresh 不调用 bind」，和 `AuthAggHandler.refresh` 的实际行为（幂等 bind）不一致，已按代码改正 |

已经更新的决策类文档：`antique/docs/architecture.md` 中「设备校验」一条（由「暂不做」改为已决定的方案）。

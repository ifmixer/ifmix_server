# Wire 加密（x-proto-version: 2）— 设计规格

日期：2026-10-04
状态：已评审（2026-10-05，安全模型结论与演进决策见 §9）
范围：服务端 `ifmix_server/core-api` + 客户端 `antique/apps/shared`、`antique/apps/antique`
目标：对全部 GraphQL 操作（白名单机制已移除，所有接口加密）的请求/响应 body 做应用层加密，抓包（含设备持有者自己装证书抓包）看不到明文；
老客户端不受影响；性能与流量「不比 v1 差」。

---

## 1. 背景、威胁模型与非目标

- 网络第三方已由 HTTPS 防住。本设计防的是**设备持有者抓包 / 拆包**：
  - 拆包只能拿到服务端**公钥**，解不了任何流量（含别人的）。
  - 抓包看不到明文 body。

### 与 attest 的分工（安全定位）

wire 加密**不是安全边界，而是成本清除器**：它保证伪造流量没有廉价的产生方式，必须穿过体系中最贵的
一道门——硬件背书的设备信任（attest：Play Integrity / App Attest）。组合后的滥用路径穷举：

- **动态逆向**（root/越狱 + Frida hook 加密前数据）：被 attest 封死——hook 需要 root/越狱/重打包，
  attestation 随之失败；
- **静态构造**（逆向包内协议代码、自行实现加密客户端）：可行，但产物没有合法 attestation → 服务端拒收；
- **唯一幸存路径**：真机 + 真 app（设备农场 / UI 自动化）——纯经济学问题（设备成本 + 封号风险）。

AI 把「理解协议 + 写构造客户端」的成本压到小时级，因此 **attest（而非协议保密性）才是承重层**；
wire 的作用是封死绕过 attest 的动态路径。纪律：**服务端永不信任客户端**——配额、付费、entitlement、
幂等全部服务端裁决；加密与 attest 只提高「假装合法客户端」的成本，永不构成「请求可信」的证据。

- **非目标**：
  - 防 root/越狱设备上 Frida hook 加密前数据（客户端加密的天花板）。
  - 防语义级重放（同一业务操作重复提交）：归幂等设计（docs/design/infra/idempotency.md）与敏感操作的一次性服务端凭据（§9）。字节级重放另见 §9 的 ts 时效决策（2026-10-05 修订：暂缓实施，维持 warn）。
  - 隐藏 `Authorization` header：v2.1 规划 header 进 body 收掉这个尾巴（§9）。
  - 强制 v2：服务端继续接受明文 v1；何时拒绝 v1 另行决定。
- 加密不替代服务端鉴权 / 限流 / App Check（App Check 由另一份规格负责）。

### 适用范围

客户端对**所有** GraphQL 操作加密（gqlOp 持有 wire 参数且本进程未降级即加密）；名单机制不存在。
服务端 filter 按 `x-proto-version: 2` 头生效，与路径无关，从来不需要白名单。

## 2. 线上格式与算法

算法：X25519 + HKDF-SHA256 + AES-256-GCM（tag 16 字节）。客户端 react-native-quick-crypto（OpenSSL，原生）；服务端 JDK 25 内置。

选 AES-256-GCM 而非 ChaCha20-Poly1305：两端都是原生实现，手机 CPU 有 AES 硬件指令更快；
每请求独立派生密钥，两者在 nonce 风险上无差别。

```
shared = X25519(ephPriv, serverPub[kid])            # eph 每请求新生成
okm    = HKDF-SHA256(ikm=shared, salt=ephPub‖serverPub, info="ifmix-wire-v2", L=64)
reqKey = okm[0..32)    resKey = okm[32..64)
```

**请求**（`Content-Type: application/octet-stream`，头 `x-proto-version: 2`）：

```
ver(1)=2 | kid(1) | ephPub(32) | nonce(12) | AES-GCM(reqKey, ts_ms(8, big-endian) ‖ 原 JSON) ‖ tag(16)
AAD = 前 34 字节（ver|kid|ephPub）
```

请求不压缩（GraphQL 变量很小）。AAD 不含 path（CF/nginx 可能改写路径）。

**响应**（`Content-Type: application/octet-stream`，头 `x-proto-version: 2`，HTTP status 保持原值）：

```
flags(1) | nonce(12) | AES-GCM(resKey, payload) ‖ tag(16)
AAD = ephPub ‖ flags
flags bit0 = 1 → payload = gzip(原文)；bit1–7 保留，必须为 0
```

- 原文字节数 **> 4096** 时服务端先 gzip 再加密，否则 flags=0。
- 客户端遇到未知 flag 位按解密失败处理。

**已知取舍（压缩后加密的长度泄露，CRIME/BREACH 类）**：风险很低，原因：
- 含 token 的响应（createInstall / createAnonymous）远小于 4KB，不压缩；
- 大响应（扫描 / 深度研究结果）不含凭证；
- 抓包者无法往他人响应注入可控内容。

## 3. 服务端

全部在 `core-api/src/main/kotlin/com/ifmix/core/api/infra/http/`。

### `WireCrypto`（纯逻辑，无 Spring）
- 构造：`Map<kid: Int, 32 字节 X25519 私钥>`；`parse("kid:base64,kid:base64")`。
- `open(payload) → Opened(body, clientTsMs)`：
  - 格式非法 / 未知 kid / 认证失败 / 低阶点一律抛 `WireCryptoException`，不区分原因。
- `Opened.seal(plain) → bytes`：
  - 内部按 4096 阈值决定 gzip（`GZIPOutputStream`）与 flags；
  - flags 只在这里定义。

### `WireCryptoFilter`（HTTP 适配）
- `@Order(HIGHEST_PRECEDENCE + 1)`，在 `RequestLoggingFilter`（+5）外层：日志、`GraphQlHttpStatusFilter`、GraphQL 看到的都是明文。
- 只处理 `x-proto-version: 2`；其余原样放行（v1 不变）。
- 请求解密后包装为明文 JSON 请求：`Content-Type`、`Content-Length` 还原。
- 缓存内层响应，`seal` 后写回，保留 status。
- 时钟偏差 > 5min：`wire.clock.skew` warn，不拒绝（已决策改为拒绝 + 独立错误码防误降级，见 §9，待实施）。
- 解密失败：**明文** 400 + `{"code":"400003","msg":"bad encrypted payload","data":null}`，`wire.decrypt.failed` warn（带 kid）。成功日志也带 kid，用于轮换时观察旧 kid 流量。
- 错误码：`ErrorCode.WIRE_DECRYPT_FAILED("400003")`；头常量：`RequestHeaders.PROTO_VERSION`。

### 配置
- `app.wire-crypto.keys`，生产由 env `WIRE_CRYPTO_KEYS` 注入；为空 = 不支持 v2，v2 请求收到 400003，客户端会降级。
- local/dev 共用一把开发 key（kid=1），写在 `application-local.yml`；prod key 只放 `/opt/app/env`。

### 性能
- 每请求多一次 X25519 + HKDF + AES-GCM（微秒级）。
- gzip 只对 >4KB 的响应做，30KB 约 0.2ms。

## 4. 客户端

### `apps/shared/src/api/wireCrypto.ts`（协议层，不依赖 RN）
- `sealRequest(key: WireKey, body: string, crypto: WireCryptoImpl) → { payload: Uint8Array, open(res: Uint8Array): string }`。
- `WireCryptoImpl` 是 Node `crypto` 的子集接口：
  - `generateKeyPairSync('x25519')`
  - `diffieHellman`
  - `hkdfSync`
  - `createCipheriv` / `createDecipheriv('aes-256-gcm')`
  - `randomBytes`

  app 注入 quick-crypto，jest 注入 `node:crypto`。shared 不直接依赖原生库。
- `open`：校验 flags → AES-GCM 解密 → bit0 时 `fflate.gunzipSync` → UTF-8 解码。
- 依赖变化：删除 `@noble/curves`、`@noble/hashes`、`@noble/ciphers`；新增 `fflate`（固定版本，只用 gunzip）。

### `apps/shared/src/api/graphql.ts`
- `GqlOpts.wire?: { key: WireKey; crypto: WireCryptoImpl }`。gqlOp 在 `wire` 存在且本进程未降级时，对所有操作加密（无 apiName 白名单）。
- `sendMaybeEncrypted` 负责加密、发送、降级（第 5 节），并记录 `sealMs` / `openMs` / `resBytes`，通过回调上报。

### `apps/shared/src/api/client.ts`
- `ServerApiOptions.getWire?: () => { key, crypto } | undefined`：每次请求求值。
- `ServerApiOptions.onWireMetrics?: (m: { apiName, sealMs, openMs, resBytes, gzip }) => void`：回调自身抛错时吞掉。

### `apps/antique/src/lib/wireCrypto.ts`（app 接线）
- 注入 `react-native-quick-crypto`。
- feature flag：
  - OTA 默认值 `DEFAULT_WIRE_CRYPTO_ENABLED = false`；
  - Developer 页面「Network → Encrypt API Payload」本地覆盖（AsyncStorage `antique.devWireCryptoEnabled`）。
- `currentWire()`：开关开、当前环境 preset 有 `wireKey`、`Platform.OS !== 'web'` 三者都满足才返回，否则 undefined（走明文）。
- 指标：`onWireMetrics` → `logEvent('wire_crypto', …)`，release 抽样 1%，`__DEV__` 全量打印日志。
- 删除为 noble 加的 `getRandomValues` 补丁。

### 原生与发布
- 用 `pnpm --filter @ifmix/antique exec expo install` 安装 `react-native-quick-crypto`（1.1.7）、`react-native-nitro-modules`、`react-native-quick-base64`，固定版本。
- `app.config.js` 加 quick-crypto config plugin；`runtimeVersion` 从 `'2'` 升到 `'3'`，重新 Archive。
- runtime 2 的老包收不到新 JS，保持明文 v1，因此无需纯 JS 兜底。

### 公钥
- `env.ts` 的 `EnvPreset.wireKey?: { kid, pubHex }`，local/dev 用开发公钥，prod 待生成后填入。

### 性能预期
- 加密一次 + 解密一次 < 1ms（原生）。
- >4KB 响应多一次 JS gunzip：本机 `--jitless` 实测最大 DR 结果（14.5KB → 6KB）约 1.5ms，低端机预估 6ms 左右。

## 5. 错误处理与降级

原则：加密失败不能让正常用户用不了 app。

| 场景 | 服务端 | 客户端 |
|---|---|---|
| 老服务端 / 回滚，不认识 v2 | 415 | 明文重发一次；本进程后续都走明文 |
| 未配 key / kid 已删 | 明文 400 + 400003 | 同上 |
| 请求篡改 / 格式非法 | 明文 400 + 400003 | 同上 |
| 设备时钟偏差 > 5min | 正常处理 + warn（ts 时效实施后改为独立错误码拒绝，见 §9） | 无感（实施后提示校时，不降级） |
| 网关错误页（CF 5xx / HTML） | 明文 | 按 content-type 不是 octet-stream 识别，交给现有错误处理（同 v1） |
| 响应解密失败 / 未知 flag / gunzip 失败 | — | **不重发**，抛 `SERVICE_UNAVAILABLE` |
| 客户端加密本身抛错 | — | 本次直接发明文；本进程后续都走明文 |

- 415 / 400003 可以安全重发：解密在服务端最外层 filter，业务还没执行。
- 全接口模式（无白名单）下，415 / 400003 触发的进程级降级影响整个 app——**所有**接口降为明文，不再是单接口粒度；这是接受的取舍。
- 响应解密失败不重发：业务可能已执行（例如扣了配额）。
- 降级状态只在进程内有效，重启 app 后重新尝试；代码里用 `ponytail:` 注释标注这个取舍。

## 6. Key 管理、轮换与上线

### 生成
```bash
openssl genpkey -algorithm X25519 -out k.pem
openssl pkey -in k.pem -outform DER | tail -c 32 | base64                 # 私钥 → WIRE_CRYPTO_KEYS="<kid>:<这里>"
openssl pkey -in k.pem -pubout -outform DER | tail -c 32 | xxd -p -c 64   # 公钥 hex → env.ts wireKey.pubHex
rm k.pem
```
kid 取值 1–255。

### 轮换（每年一次，或私钥泄露时）
1. 服务端 `WIRE_CRYPTO_KEYS` 加新 kid（新旧并存），部署。
2. 客户端 OTA，把 `env.ts` 换成新 kid 的公钥。
3. 按 kid 观察日志，旧 kid 流量降到可接受水平后删除旧 kid。仍用旧 kid 的客户端会自动降级为明文，不会用不了 app。
4. 私钥泄露时：第 3 步不等，立即删除旧 kid。

### 上线顺序
1. 服务端配好 dev / prod key 并部署。客户端未开开关，此步无影响。
2. 发新原生包（runtime 3），prod 公钥写入 `env.ts`，默认开关 `false`。
3. 内部验证：用 Developer 开关在低端 Android + iPhone 上实测。
4. 全量门槛：低端 Android 上 `sealMs + openMs` 的 p95 < 10ms。满足后 OTA 把默认开关改为 `true`；出问题时 OTA 改回 `false`（kill switch）。
5. 全量前用 curl 对比一次流量：抽多个大响应接口，v1 经 CF 的传输大小 vs v2 密文大小。

## 7. 测试

### 服务端 `WireCryptoTest`
- 往返测试：测试侧用 JDK 独立实现客户端。
- gzip 阈值：4096 → flags=0；4097 → flags=1，且能解压还原。
- 多 kid、未知 kid、请求篡改、flags 篡改、空配置。
- filter：保留 status；坏 payload 返回明文 400003；v1 原样放行。

### 跨语言固定向量（固定 ephPriv / nonce / ts）
- 请求向量：客户端（node:crypto 注入）生成，服务端解开。
- 响应向量：服务端生成，含 gzip / 非 gzip 两条，客户端解开。
- 任一端改算法，另一端测试即失败。

### 客户端 shared（注入 `node:crypto`）
- `wireCrypto.test.ts`：对独立的 node:crypto 服务端实现做往返；篡改；未知 flag；gzip 响应；固定向量。
- gqlOp 测试：
  - 所有操作加密；降级后保持明文；
  - 415 / 400003 降级，降级后保持明文；
  - 响应解密失败不重发；
  - 明文错误页照常处理；
  - 回调指标。

### 客户端 app
- jest 把 `react-native-quick-crypto` 映射到 `node:crypto`。
- `currentWire()` 在开关关闭 / 无公钥 / web 时返回 undefined。
- 移除为 noble 加的 babel 转换和 TextEncoder 补丁。

### E2E
- 本地 `bootRun`，用 shared client 跑代表性接口集（小响应 + 一条 >4KB 大响应，覆盖 gzip 分支）。临时脚本，跑完删除。
- 真机：用 Developer 开关手动验证。

## 8. 相对已实现版本（未提交）的改动清单

- 服务端：
  - `WireCrypto` 改为 AES-256-GCM；`seal` 加 flags + gzip；日志带 kid。
  - Filter / 配置 / 错误码 / 头常量不变。
- 客户端 shared：
  - noble 换成注入式 `WireCryptoImpl`；加 fflate；
  - `GqlOpts.wireKey` 改为 `wire`；加 `onWireMetrics`。
- 客户端 app：
  - 加 quick-crypto 三个原生依赖、config plugin、runtimeVersion 3；
  - `currentWire()` 加 web 排除；指标上报；
  - 删 noble 相关的 jest 配置和随机数补丁。
- 测试：按第 7 节补齐；重新生成跨语言向量。
- 白名单机制移除，所有 GraphQL 操作加密（服务端无协议改动，filter 本与路径无关）。

## 9. 安全模型结论与演进路线（2026-10-05 评审）

### 分层定位

HTTPS 管传输（网络第三方）、wire 管设备持有者（HTTPS 终结后的 body）、attest 管设备真实性、
服务端裁决管一切决策。wire 的价值是让前两道门前面不再有免费的侧门（详析见 §1「与 attest 的分工」）。

### 已决策：保留降级与 kill switch（不做"去降级"）

- HTTPS 之下降级是零成本保险：服务端事故（漏配 key / 坏版本 / 回滚）全体静默降明文照常可用，
  kill switch（OTA 关 flag）永远可用。去掉它，任何 crypto 相关的机型 bug 的爆炸半径从
  「这些设备静默走明文」变成「这些设备完全不可用」。
- 降级信号（415 / 400003）是未认证明文：HTTP 下中间人可伪造它强制全体降级（SSL-stripping 变体）——
  这也是 HTTPS 必须保留的理由之一。
- 「服务端拒绝明文 v1」维持另行决定：强制 v2 之日 = 未升级老版本全体不可用，需等渗透长尾（6–18 个月）。

### 已决策：HTTPS 保留，不换 HTTP

- wire 只覆盖 body，且仅在 flag 开 / 有公钥 / 未降级时生效；header（含 Authorization）、
  信封外流量（图片下载、OTA 更新包、第三方 SDK、webhook）、容器错误页全在加密之外——
  HTTP 下全部成为网络攻击面，且降级信号可被武器化（上一条）。
- 平台强制（iOS ATS / Android cleartext）与 CF 架构均按 HTTPS 建立；TLS 开销在 CF 终结 + 硬件 AES
  下微不足道，无可换的收益。

### 已规划：v2.1 header 进 body（收掉 token 在 header 的尾巴）

- **线协议不动**（ver=2 字节格式不变）：仅改加密前的 JSON 载荷为
  `{"meta": {…敏感头…}, "query": "", "variables": {…}}`；老客户端继续走 header，服务端并存读取。
- 服务端：`DecryptedRequest` 解密后从 body meta 供应 `getHeader("Authorization")` 等，
  AuthInterceptor / RequestParser 无感知。
- 永远留在 body 外的信封标记：`x-proto-version` / `Content-Type`（服务端要先看到它才知道要解密——鸡生蛋）
  与 CF 注入头（`cf-bot-score`、真实 IP——限流依赖）。
- 收益定位：对网络第三方无增益（HTTPS 已藏 header）；对设备持有者是收尾（token 本是其自有会话凭证）。

### 决策修订（2026-10-05）：ts 强制时效暂缓实施

- 原决策（ts |skew| > 5min 拒绝 + 独立错误码 `400004` + LRU 去重封堵字节级重放）**暂不实施**。
- 原因：客户端本地时钟与服务器可能相差很远（无法假设用户校时），强制时效会让这部分诚实用户全体不可用——独立错误码只能避免「进程级降级」，救不了「用户被拒」。宁可保留字节级重放窗口，也不拒绝时钟不准的用户。
- 现状维持：`ts` 偏差仅 warn，不做拒绝、不做 LRU 去重。字节级重放的防护定位不变——语义级防重放归幂等设计（docs/design/infra/idempotency.md）+ 敏感操作一次性服务端凭据（§9 下一条）。
- 若未来实施：仍按原方案（独立错误码 `400004` 明文返回 + (ephPub, nonce) LRU 去重），且须先解决客户端时钟同步（如服务端时间下发对时）再启用拒绝。

### 结论记录：客户端自供值不能作安全边界

- requestId / nonce / ts 均在攻击者完全控制下。「查 requestId 是否请求过」只防最懒的字节重放，
  对能构造者完全无效（每次生成新值）。请求层去重的正确定位是**传输幂等**（网络重试去重），不是安全控制。
- 防「同一业务操作重复提交」：**语义幂等**——状态机 + DB 唯一约束 + 幂等键绑定业务实体
  （scanId / purchaseToken / rewardId），见 docs/design/infra/idempotency.md。
- 敏感操作（发起扫描、领取类）：**服务端签发一次性操作凭据**（与 attest challenge 同构：
  绑定 customerId + 操作类型 + 短 TTL，消费即作废）——把防重放的锚点从攻击者可控字段
  移到服务端自有状态。

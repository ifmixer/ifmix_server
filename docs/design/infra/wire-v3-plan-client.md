# Wire v3（RFC 9180 HPKE）实现计划 — 客户端（antique）

> **给接手的 agent**：先读 ifmix_server 仓库 `docs/design/infra/wire-encryption.md` §10（v3 契约，唯一真相源）→ 本文件。契约若与本文件不一致，以 §10 为准并回改本文件。逐任务更新「状态」，**不要重做已完成任务**。
> - 仓库：`/Users/jason/ai/myprojects/antique`（`apps/shared` + `apps/antique`）
> - 服务端计划见 ifmix_server `docs/design/infra/wire-v3-plan-server.md`（双端并行，互不阻塞；联调见 §2）
> - 最后更新：2026-10-05（计划创建，全部任务 pending）

## 0. 一页纸摘要

把 `apps/shared/src/api/wireCrypto.ts` 的 v2 自研封装替换为 **RFC 9180 HPKE 标准封装**（Suite：`DHKEM(X25519, HKDF-SHA256) 0x0020` / `HKDF-SHA256 0x0001` / `AES-256-GCM 0x0002`，base mode）。新增**请求 gzip 压缩**（pt > 4096 先压后加密）。**客户端密钥对每次请求临时生成**（HPKE base mode 天然如此，后端零配合）——"不定期轮换"由请求级临时密钥直接满足，无需任何轮换调度代码。

**能不自己写的就不自己写**：HPKE 用成熟库，gzip 用现成库；禁止手写密码学原语或常数。

## 0.1 契约速查（与 §10 一致，双端必须逐字节对齐）

```
Suite:   KEM=0x0020 DHKEM(X25519,HKDF-SHA256) | KDF=0x0001 HKDF-SHA256 | AEAD=0x0002 AES-256-GCM | mode=base
info:    "ifmix-wire-v3"
请求:    ver(1)=3 | kid(1) | enc(32) | flags(1) | HPKE-Seal(pkR=kid公钥, info, aad, pt) ‖ tag(16)
         aad = ver‖kid‖enc‖flags（36B）；pt = ts_ms(8,BE) ‖ body
         flags bit0=1 → pt 整体 gzip 后再加密；pt > 4096 才压（4096 不压、4097 压）
响应:    flags(1) | nonce(12) | AES-256-GCM(resKey, nonce, aad=enc‖flags, payload) ‖ tag(16)
         resKey = HPKE-Export(context, "ifmix-wire-v3-res", 32)
         flags bit0=1 → payload gzip；未知 flag 位必须失败
config:  客户端内置 WIRE_KEY_CONFIG = { version: 3, keys: [{ kid: number, pk: base64(公钥raw32B) }] }
         （服务端侧对应 env APP_WIRE_KEYS；kem/kdf/aead 由 suite 固定，不进 config）
降级:    服务端未配 key / 明文环境 → 走现有 v1 明文逻辑（客户端降级开关不变）
```

## 1. 任务分解

### T1 选型（已定稿，实现 agent 不再选择）

- [ ] **状态：pending（选型已定：noble 组装，见下）**

**决策（2026-10-05，用户已确认授权拍板）：`@noble/curves`（X25519）+ `@noble/hashes`（HKDF-SHA256）+ `@noble/ciphers`（AES-256-GCM）按 RFC 9180 组装 base mode（约 100–150 行机械性胶水），gzip 用 `pako`。不采用 `@hpke/core`。**

理由（查证记录）：

1. **`@hpke/core` 是 WebCrypto-only 实现**——DHKEM(X25519) 依赖 WebCrypto Secure Curves 扩展；而 Hermes **没有任何 WebCrypto**，必须接 `react-native-quick-crypto` 原生模块做 polyfill。这引入三方版本矩阵（@hpke/core × quick-crypto × Expo SDK/RN）+ EAS 原生构建变更 + 社区已报告的 OpenSSL 手工链接问题——正是本项目要消除的"脆弱"。且 quick-crypto 的 X25519 WebCrypto 支持是新近加入，行为仍有版本敏感性。
2. **性能不构成采用 quick-crypto 的理由**：wire 加密每请求只跑一次，纯 JS X25519+AES-GCM 合计数毫秒，相对网络 RTT 可忽略。
3. **noble 系经审计**（noble-curves/noble-hashes：cure53 2022；noble-ciphers 亦经审计），是 JS 生态事实标准；纯 TS 无原生依赖，Hermes 直跑（Hermes 支持 BigInt；唯一 polyfill 是 `react-native-get-random-values` 提供 `crypto.getRandomValues`，标准做法）。
4. **双端独立实现是加分项**：服务端用 BouncyCastle、客户端用 noble 组装——两套独立实现靠同一组官方向量互锁，比"两端依赖同一个 JS 库"更能抓实现 bug。
5. 自己组装的唯一风险（HPKE key schedule 拼装错误）由 hpkewg 官方向量在 CI 中逐字节锁定——这正是本计划测试策略的主防线。

任务：安装上述三个 noble 包 + `pako` + `react-native-get-random-values`（若项目尚未有）；确认 Hermes BigInt 可用（Expo SDK 现版本已支持，写一条冒烟断言即可）。
- **验收**：依赖入 `apps/shared/package.json`；node 环境下用 hpkewg 官方向量跑通一轮 Seal/Open。

### T2 wireCrypto.ts 重写

- [ ] **状态：pending** ｜ 依赖：T1
- 重写 `apps/shared/src/api/wireCrypto.ts`：
  - 导出接口尽量保持现有形状（client.ts / ensureInstallWhenOnline 等调用点**零改动**为先决目标）；内部全部换 HPKE。
  - `WIRE_KEY_CONFIG` 常量按契约更新为 `{ version: 3, keys: [{kid, pk}] }`（值与服务端 agent 对齐，见 §2 联调）。
  - 请求：序列化 body → 拼 `ts_ms(8,BE)` → >4096 gzip → HPKE-Seal → 拼信封。
  - 响应：按 flags 解压、未知 flag 位失败、Exporter 派生 resKey 解密。
  - 每次调用生成新的 ephemeral 密钥对（HPKE 库的 Seal 天然行为；**不做**跨请求密钥缓存/轮换调度）。
- **不动**：attest、codes.ts、retry.ts、client.ts 的重试/降级编排逻辑（除非类型签名被迫变化）。
- **验收**：typecheck 0 错误；调用点 diff 仅限必要的 import/签名对齐。

### T3 测试

- [ ] **状态：pending** ｜ 依赖：T2
- 官方向量用例：从 HPKE 工作组向量（与服务端同一来源，见服务端计划 T1）驱动 Seal/Open，逐字节对拍。
- 端到端 round-trip：请求密文→（模拟服务端）解密→响应→客户端解密；覆盖 >4KB 请求 gzip、4096/4097 边界、未知 flag 拒绝、坏 kid、坏 enc、降级路径（服务端无 key → 明文）。
- 既有 wire 相关测试同步迁移（v2 的用例中与信封格式绑定的部分重写，与降级/编排相关的保留）。
- **验收**：现有测试套件全绿 + typecheck。

### T4 收尾与联调（依赖双端 T3 完成）

- [ ] **状态：pending**
- 真机/模拟器联调：连本地服务端跑通 createScan（构造 >4KB 请求验证压缩）+ 大响应 gzip + 降级路径。
- 联调时核对三项易错点：① 双端 key config 的 kid/pk 一致；② ts 字节序（BE）与拼接顺序（ts 在前）；③ flags 位定义一致（请求 bit0=gzip、响应 bit0=gzip、其余必须 0）。
- 回写本文状态 + ifmix_server 侧 `docs/design/infra/wire-encryption.md` §10.4 步骤 2 勾选（需要动 server 仓库时与服务端 agent 协调）。

## 2. 执行顺序与并行

```
T1(选型) → T2(重写) → T3(测试) → T4(联调)
```

- **并行度建议：2**——本计划的 agent 与服务端计划（wire-v3-plan-server.md）的 agent 同时跑；T4 联调在双端 T3 完成后由 1 个 agent 执行（两个仓库路径都在本机）。
- 若要占满 4 个 subagent：T1 可独立（选型+向量对拍脚本先行），T2、T3 分开——但收益有限，不强制。
- **跨仓库对齐点（双端各自由计划携带，无需额外沟通）**：契约速查一致即可；唯一需要联调确认的是 `WIRE_KEY_CONFIG` 的实际值。

## 3. 禁止事项

1. **禁止手写/手誊任何密码学常数**；HPKE 输入校验交给库（服务端黑名单教训见 §10.5）。
2. 不做 OHTTP（relay/gateway）——设计已否决（§10.0）。
3. 不动 attest / retry / 限流 flag / persisted queries。
4. 不实现"客户端长期密钥 + 服务端注册"类方案（设计明确禁止，见 §10.2）。
5. 不自行 commit（除非用户明确要求）。

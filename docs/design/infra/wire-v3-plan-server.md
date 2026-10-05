# Wire v3（RFC 9180 HPKE）实现计划 — 服务端（ifmix_server）

> **给接手的 agent**：先读 `docs/design/infra/wire-encryption.md` §10（v3 契约，唯一真相源）→ 本文件 → `AGENTS.md`。契约若与本文件不一致，以 §10 为准并回改本文件。逐任务更新「状态」，**不要重做已完成任务**。
> - 仓库：`/Users/jason/ai/myprojects/ifmix_server`（与用户对齐工作分支：main 或 `feature/1.0.6-code-review`）
> - 前端计划见 `docs/design/infra/wire-v3-plan-client.md`（另一仓库，不归你改）
> - 最后更新：2026-10-05（计划创建，全部任务 pending）

## 0. 一页纸摘要

把 wire 加密 v2 的自研信封替换为 **RFC 9180 HPKE 标准封装**（Suite：`DHKEM(X25519, HKDF-SHA256) 0x0020` / `HKDF-SHA256 0x0001` / `AES-256-GCM 0x0002`，base mode）。v2 从未上线，**直接替换、不保留 v2 解析路径**；明文 v1 降级保留。新增：请求 gzip 压缩（>4096，解压上限 1MB 防 zip-bomb）。客户端密钥轮换是前端自治行为（HPKE base mode 下后端只消费请求内的 enc），**后端无需任何配合代码**。

**能不自己写的就不自己写**：密码学全部来自 BouncyCastle HPKE 库，禁止手写任何原语或常数。

## 0.1 契约速查（与 §10 一致，双端必须逐字节对齐）

```
Suite:   KEM=0x0020 DHKEM(X25519,HKDF-SHA256) | KDF=0x0001 HKDF-SHA256 | AEAD=0x0002 AES-256-GCM | mode=base
info:    "ifmix-wire-v3"
请求:    ver(1)=3 | kid(1) | enc(32) | flags(1) | HPKE-Seal(pkR=kid公钥, info, aad, pt) ‖ tag(16)
         aad = ver‖kid‖enc‖flags（35B，1+1+32+1；flags 参与 AAD 防翻转）
         pt  = ts_ms(8,BE) ‖ body ；flags bit0=1 → pt 整体 gzip 后再加密
         pt > 4096 才 gzip（4096 不压、4097 压）
响应:    flags(1) | nonce(12) | AES-256-GCM(resKey, nonce, aad=enc‖flags, payload) ‖ tag(16)
         resKey = HPKE-Export(context, "ifmix-wire-v3-res", 32)；nonce 每响应 SecureRandom 现生成
         flags bit0=1 → payload gzip（阈值 4096，同 v2）；未知 flag 位按失败处理
配置:    服务端 env APP_WIRE_KEYS = "kid:base64(私钥raw32B),..."（格式同 v2）
         客户端内置 config = { version: 3, keys: [{ kid, pk: base64(公钥raw32B) }] }
错误:    一切解密失败（格式/kid 未知/HPKE 失败/解压超限）→ 明文 JSON 400003，不区分原因
降级:    无 x-proto-version: 3 → 按 v1 明文放行（不变）
```

## 1. 任务分解

### T1 依赖与向量文件（可与 T2 并行跑脚本，T3 测试前必须就绪）

- [ ] **状态：pending**
- `core-api/build.gradle.kts` 加 `org.bouncycastle:bcprov-jdk18on`（≥1.77，确认与 JDK 25 兼容的最新版）。
- 写脚本 `core-api/src/test/resources/wire-v3/gen_vectors.py`（或 Kotlin 脚本）：从 `https://raw.githubusercontent.com/hpkewg/test-vectors/main/` 下载对应 suite 的 JSON（`DHKEM(X25519,HKDF-SHA256)_HKDF-SHA256_AES-256-GCM` base mode），**抽取本项目需要的字段**（enc/apu/apd/ct 与 key/expected）生成精简向量文件 `wire-v3-rfc-vectors.json`，文件头注明来源 URL 与 commit hash。
- **验收**：向量文件生成可复现（脚本重跑 diff 为空）；无任何手誊 hex。

### T2 HPKE 实现替换（核心）

- [ ] **状态：pending** ｜ 依赖：无（可与 T1 并行写代码）
- 重写 `core-api/src/main/kotlin/com/ifmix/core/api/infra/http/WireCrypto.kt`（类名与公开接口 `open/seal/parse/isEnabled` 保持不变，Filter 零改动）：
  - `parse` 逻辑不变（env `APP_WIRE_KEYS`，kid 1..255）。
  - `open`：按契约速查解析 → BC HPKE `open` 消费 enc → 按 flags 解压（**超 1MB 上限抛 WireCryptoException**，上限走配置 `app.wire.max-decompressed-bytes`，默认 1048576）→ 返回 `Opened`（保留 clientTsMs/kid/enc 供 seal 用）。
  - `Opened.seal`：HPKE `Export("ifmix-wire-v3-res", 32)` 派生 resKey → AES-256-GCM（nonce 每响应 SecureRandom）→ flags/gzip 阈值 4096 逻辑不变。
  - **删除** v2 全部遗留（自定义 HKDF 拼装、ZERO_32 检查、TS 常量中已不需要的项按实际清理）。
- `WireCryptoFilter.kt`：仅把版本判断从 2 改为 3（如有硬编码）；顺序、400003 明文错误、降级逻辑**零改动**。
- `application.yml`：`app.wire.*` 段更新（keys 格式不变，新增 max-decompressed-bytes）。
- **验收**：编译过；全文件 grep 无手写密码学常数（ZERO/hex 常量表）；Filter/降级路径 diff 最小。

### T3 测试

- [ ] **状态：pending** ｜ 依赖：T1 + T2
- `WireCryptoTest.kt` 改造：
  - RFC 向量用例：从 T1 的 JSON 驱动（至少 base mode Seal/Open 各若干组），断言与官方向量逐字节一致。
  - 端到端 round-trip：seal→open→sealResponse→验证（含 >4KB 请求 gzip、4096/4097 边界、解压超限拒绝、未知 flag 位拒绝、未知 kid、坏 enc）。
  - 降级：`WireCrypto.parse("")` → v1 明文放行（既有用例保留）。
- **验收**：`./gradlew :core-api:test --tests '*WireCrypto*'` 全绿 + 全量 `:core-api:test` 通过。

### T4 收尾与文档（本仓库侧）

- [ ] **状态：pending** ｜ 依赖：T3（可与前端 WP-I 并行）
- 回写本文各任务状态；`docs/design/infra/wire-encryption.md` §10.4 步骤 1 勾选。
- `docs/ops/release.md`（v1.0.6 发布计划）与 `docs/ops/Changelog.md` 回写：wire v3（HPKE + 请求压缩），注明「v2 从未上线，无兼容负担」。
- 检查 AGENTS.md / DESIGN 索引中 wire 行的描述是否仍准确。

## 2. 执行顺序与并行

```
T1(向量脚本) ─┐
              ├─→ T3(测试) ─→ T4(收尾)
T2(实现) ─────┘
```

- **并行度建议：2**——本计划 T1+T2+T3 由 1 个 agent 串行即可（T1 脚本可与 T2 并行写）；真正的大并行是 **本计划的 agent 与前端计划（wire-v3-plan-client.md）的 agent 同时跑**。
- 若要占满 4 个 subagent：T1 可独立成 1 个 agent（纯脚本 + 向量文件），T2+T3 一个，前端 impl / 前端测试各一个——共 4。

## 3. 禁止事项

1. **禁止手写/手誊任何密码学常数**（低阶点黑名单的教训：三方手算三方错）。
2. 不做 OHTTP（relay/gateway）——§10.0 已否决。
3. 不动 attest、限流、LogContext、GraphQlHttpStatusFilter（除 wire 版本判断）。
4. 不引入第二套加密路径；v2 代码路径删除而非注释保留。
5. 不自行 commit（除非用户明确要求；commit 前按 AGENTS.md 跑 GitNexus detect-changes）。

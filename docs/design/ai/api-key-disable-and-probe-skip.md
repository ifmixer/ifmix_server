# AI Key 池演进：401/403 永久禁用 + pick 全冷却跳窗口

日期：2026-10-04 ｜ 状态：**已实现（2026-10-04）**（`AiConfig.disableKeyFn` + `SpringAiScanRunner` 跳窗口，测试 `AiConfigDisableKeyDbTest`/`AiConfigDisableKeyFnTest`/`SpringAiScanRunnerDisableTest`）｜ 基线：[api-key-pool.md](api-key-pool.md)（2026-10-01 已实现）

本文只描述本次两项改动。key 池的轮询/冷却/降级基线见基线文档，不在此重复。

## 背景与问题

基线实现里，401/403 只做 Redis 1h 冷却、`pick()` 全冷却时游标只推进 1。两个独立问题：

### 问题一：坏 key 周期性回池重烧

401/403 冷却 1h 后自动解冻，坏 key 回池再次 401 → 再冷却，循环烧 key（每次最多浪费 `MAX_ATTEMPTS_PER_MODEL=4` 次尝试）。基线代码只靠 `log.error` 提示运营手工把 key 表改 `enabled=false`，但**从未自动落地**：

- `AiApiKeyRepository.save` / `findById` 在 main 代码中无任何调用点（仅构造注入到 `AiConfig`，未使用）
- 实体字段 `unavailableUntil`（`AiApiKey.kt:41`）是死字段——全仓库无读写逻辑，疑似为自动禁用预留但未实现

### 问题二：连续冷却区段卡掉整请求

`pick()` 全冷却时游标只推进 1，探测窗口 `probe=5` → 连续两次 `pick` 的窗口起点差 1、**重叠 4/5**。注意「连续区段」不会来自导入聚集——`loadKeys` 加载时已 `shuffled()`，正是为了切断导入的相邻热点。真实会形成全冷却窗口的是两条路径：

- **风暴顺路打冷却**：命中时游标净 +1，key 近似按游标顺序被顺序消费——429 风暴期间，游标路径后方会留下一整段刚被打上冷却的连续 key（300s 内全部不可用）；
- **高冷却占比**：provider 大面积故障/限流时冷却占比 f 很高，即使均匀散布，P(窗口全冷却) ≈ f^probe（f=0.9 → 59%，f=0.5 → 3%）——占比越高，全冷却窗口越常见。

一旦撞上这样的区段：

- 单请求 4 次 attempt 只把游标推进 4 步 → 几乎必然整请求 `AI_UNAVAILABLE`
- 游标是实例级 `AtomicLong`、跨请求累积，需约 `区段长度 / 4` 个失败请求才能"爬出"（100 个连续冷却 key ≈ 25 个失败请求）

## 改动一：401/403 永久禁用（PG `enabled=false`）

### 分层判定（关键决策）

| 判定道 | 来源 | 动作 | 可逆性 |
|---|---|---|---|
| **类型化异常** | cause 链含 `UnauthorizedException` / `PermissionDeniedException` | 1h 冷却 **+ PG `enabled=false`** | 不可逆，需人工恢复 |
| **message 兜底** | 短语 `"unauthorized"` / `"invalid api key"` / `"forbidden"` / `"permission denied"` | 仅 1h 冷却 | 可逆，1h 自动解冻 |

理由：`markCooldown`（Redis）可逆、误判代价低；`enabled=false` 不可逆、需人工恢复。类型化异常是 API 确实返回 401/403 的强信号；message 兜底可能被网关自定义错误页、SDK 包装异常的 message 误中（与 `classify` 早已删除纯数字匹配同源的误判风险）。**不可逆操作只走高置信度路径。**

### `classify` 返回值

`Pair<Long, String>` → data class：

```kotlin
internal data class KeyFailure(val cooldownSec: Long, val reason: String, val typedInvalidKey: Boolean = false)
```

- `reason` 语义不变（仍是 `"401"`/`"403"`/`"429"`/`"timeout"`/`"error"`，写 Redis value 与日志供排查），新增 `typedInvalidKey` 只决定是否禁用
- 403 的判定本身即 `PermissionDeniedException` 类型化判定 → `reason=="403"` 恒为 `typedInvalidKey=true`
- 只有 `"401"` 需区分类型化（`UnauthorizedException`）与 message 兜底

各分支：
- 429 → `KeyFailure(rateLimitedSec, "429")`
- 失效 key：先判 `chainOf(e).any { it is UnauthorizedException || it is PermissionDeniedException }`
  - 命中 → `KeyFailure(invalidKeySec, if (PermissionDeniedException) "403" else "401", typedInvalidKey = true)`
  - 仅 message 兜底 → `KeyFailure(invalidKeySec, "401", typedInvalidKey = false)`
- timeout → `KeyFailure(timeoutSec, "timeout")`
- other → `KeyFailure(otherSec, "error")`

### 落地路径（seam 模式，runner 不依赖 `ModuleCtx`）

key 池操作是 infra 级，沿用 `AiConfig.loadKeys` 用全局 `sqlClient`、不依赖 `ModuleCtx` 的先例（scan 在 GraphQL 请求内同步执行、也在后台虚拟线程池执行，两种上下文都不为 key 池引入事务依赖）：

```
AiApiKeyStore:  新增构造 seam disableKeyFn: (keyId: String) -> Unit，暴露 disableKey(keyId)
                （与 markCooldownFn 同模式；AiApiKeyDoc.id 已是 UUID 字符串，直接透传）

AiConfig:       val affected = sqlClient.createUpdate(AiApiKey::class) {   // 项目惯例（InstallRepository 等 6 处先例）
                    where(table.id eq UUID.fromString(keyId))
                    where(table.enabled eq true)      // 2026-10-10 加：保证重复禁用是**真**幂等
                    set(table.enabled, false)
                    set(table.updatedAt, Instant.now()) // 2026-10-10 加：表无 ON UPDATE trigger
                }.execute()
                affected > 0 → WARN（key 已禁用，提示运营确认）
                affected = 0 → INFO（已禁用或已删除，幂等 no-op）
                catch (e: Exception) → warnDegraded + return false（禁用是 best-effort 副作用，整体收敛：
                    Jimmer 异常不经 Spring 翻译、不是 DataAccessException，按类型列举兜不住；
                    不变量 =「任何异常不得逃出 disableKeyFn」）
                返回值 Boolean：true = DB 已确认该 key 不在 enabled 集合（写成功，含 affected=0 的重复禁用）；
                  false = 写失败

为什么是这两个条件（2026-10-10）：
  · `updated_at`：`core_ai_apikey` 无 ON UPDATE trigger，Jimmer 局部更新只写 set 过的列。
    不写的话 disabled 行的 updated_at 停在插入时刻，排障无法判断"何时被自动禁用"——
    2026-10-09 排查时就踩了：`SELECT ... WHERE updated_at BETWEEN` 圈不出任何自动禁用，
    只能翻滚动日志。写完 updated_at = "状态被改成 disabled 的时刻"。
  · `enabled eq true`：只加 updated_at 会让重复禁用每次 affected=1（时间戳变了），
    updated_at 漂移到"最后一次重试禁用"的时间，同时每次都打一条似是而非的 WARN。

AiApiKeyStore:  disableKey(keyId) = if (disableKeyFn(keyId)) evict(keyId)
                evict 把该 key 从本进程列表立即剔除（2026-10-09 加，见 §完整时序）：
                  禁用不再等 300s 列表重载才生效，也就不必依赖 Redis 冷却去兜那段窗口

warnDegraded 参数化：现有实现硬编码 "redis failed (fail-open, cooldown disabled ...)"，
DB 写失败复用会打出与实际故障/后果都不符的误导消息——op 与原因改为入参（Redis 路径消息不变），
限频槽可共用（同属「key 池降级」告警），但消息必须与实际故障一致。

SpringAiScanRunner catch 分支:
                keyStore.markCooldown(doc.id, sec, reason)     // 恒执行
                if (failure.typedInvalidKey)
                    keyStore.disableKey(doc.id)                // 异常在 AiConfig 内收敛（catch Exception）
                日志按 typedInvalidKey 分 ERROR（已禁用）/ WARN（疑似失效，仅冷却）
```

Jimmer 局部更新用 `createUpdate(...).execute()`（项目已有 6 处先例：`InstallRepository`、`InstallAttestationRepository`、`TodoItemRepository` 等；`KSqlClient.executeUpdate` 虽在 0.11.5 存在，但不为同一操作引入第二种写法）。`table.id` 需显式 import 生成的扩展属性 `com.ifmix.core.api.entity.ai.id`（与 `enabled` 同源）。不引入新配置项（阈值/开关属 YAGNI，冷却时长沿用 `app.ai.apikey-pool.cooldown.invalid-key-sec`）。

### 完整时序（含两处既有缓冲）

```
类型化 401 → markCooldown(Redis 1h) + disableKey(PG enabled=false)
  ├─ 本地：写库成功 → AiApiKeyStore.evict 立即把 key 从本进程列表剔除（2026-10-09 起，
  │        原先要等 REFRESH_TTL_SEC=300s 重载）
  └─ 其他实例：仍各自缓存，最长 300s 后重载剔除；这段窗口由 Redis 1h 冷却覆盖

降级链：
  DB 写失败     → 限频 WARN，**不本地剔除**（此时 Redis 1h 冷却 + 300s 重载是唯一兜底；
                  提前剔除会让一个其实还能用的 key 白掉一个轮换位），1h 后回池再 401 时重试禁用（幂等）
  Redis 同时故障 → 最多再 401 一次（本实例已剔除；其余实例等重载），禁用幂等 + MAX_ATTEMPTS_PER_MODEL=4 上界兜住
```

为什么不本地剔除反而更危险：列表陈旧窗口原先**只**由 Redis 冷却守护——一旦冷却键丢失（Redis 重启：
生产 `appendonly=no` + RDB 定时落盘）或 MGET 降级 fail-open，已禁用的 key 会被再挑出去一次
（2026-10-09 排查记录的正是这条路径）。写成功即剔除把这层依赖去掉，Redis 只剩跨实例同步这一个职责。

**误杀面三层控制**：类型化异常（而非兜底短语）+ ERROR 日志（1h 发现窗口）+ Redis 1h 缓冲。恢复方式：运营改 `enabled=true` 或删除该行。

## 改动二：`pick()` 全冷却跳窗口

```
pick():
  start  = cursor.getAndIncrement() mod n     # 每 pick 必 +1（保持原子递增语义）
  cands  = keys[start, start+probe)
  hit    = cands 中第一个未冷却者，或 null
  if (hit == null && probe > 1)
      cursor.addAndGet(probe - 1)             # 全冷却：游标已 +1，再补 (probe-1) → 净推进 probe
  return hit
```

- **命中时游标净推进 1**（均匀轮询语义不变）
- **全冷却时净推进 `probe`**（1 + `probe-1`），下次 `pick` 从这段窗口之后开始

```
# 全冷却时（probe=5）：
attempt1: [k0..k4] 全冷却 → 游标 +5
attempt2: [k5..k9] 全冷却 → 游标 +5
attempt3: [k10..k14] 命中 k12 → 游标 +1，返回 k12      # 3 次 attempt 跳出 10 个坏 key
# 改前同样场景：attempt1..4 分别探测 [k0..k4]..[k3..k7]，重叠 4/5，4 次只爬 4 步 → 整请求失败
```

效果（连续冷却区段 100、`probe=5`）：单请求 4 次 attempt 从爬 4 步 → 爬 20 步，大概率第 1–2 个 attempt 即跳出区段命中；最坏 5 个请求爬出（原约 25）。高冷却占比场景（无连续区段、均匀散布）同样受益：全冷却窗口直接跳过整段，不再以重叠 4/5 的方式反复重探同批冷却 key。

### 并发安全

`getAndIncrement` 与 `addAndGet` 都是原子**相对增量**，无回退风险。最坏情况两个并发线程观测到同一窗口（各自返回 null、各 +`probe`），跳跃效率只增不减；命中时两线程可能取到同一 key——与现有"每请求 +1"下的表现一致（key 数量级 ~3000、`MAX_ATTEMPTS_PER_MODEL=4` 下可忽略）。不引入 CAS 自旋。

### 退化正确性

- `probe=1`（clamp 下限或 `n=1`）：额外 +0，退化为当前行为
- `n ≤ probe`：净推进 `probe` ≥ `n`，`floorMod` 归一回原位——全池冷却时跳到哪都一样返回 null，正确

## 补充改动：400/422 的两类语义（2026-10-10）

原先 catch 里 `isNonRetryableRequestError`（类型化 `BadRequestException`/`UnprocessableEntityException`）一命中就
立即终止——基于「400/422 = 请求数据本身有问题，换 key 重试无意义」。这条假设漏了一类：

**provider 回源取图失败，provider 自己把它报成 400。** 图片以 URL 形式交给 provider，由 provider
回源到我们的对象存储下载；超时/抖动时它返回：

```
400: BadRequestError: OpenAIException - {"message":"An exception occurred while loading IMAGE data
     at index 0: … Timed out while downloading media URL: https://…"}
```

这既不是图片坏、也不是 key 坏——**同样的 URL 和 key，稍后重试往往能过**（实测 agnes-3.0-flash 高发，
2.5 少见）。所以 400/422 现在分两类处理：

```
isTransientImageFetchFailure(e)   // 先要求类型化 400/422，再要求 message 命中图片装载短语
  ├─ true  → 换 key 重试，独立上界 MAX_IMAGE_FETCH_ATTEMPTS = 2
  │           **不冷却、不禁用 key**（provider 回源失败，key 无过错）
  │           上界取 2 而不是 MAX_ATTEMPTS_PER_MODEL=4：每次失败 provider 要先跑完图片下载超时才返回，
  │           放满会把 scan-deadline(600s) 耗光却仍失败
  │           耗尽才抛 INVALID_REQUEST，message 区别于真坏图片
  └─ false → 维持原语义（isNonRetryableRequestError）：立即终止、不冷却、不重试
```

两道判定缺一不可：只按 message 匹配会让普通超时/错误因为路径里带了 media URL 字样就被劫持到重试分支
（有单测锁定：非 400 + 该短语 → 仍走正常 timeout 冷却路径）。

判定的前提是「重试安全」：图片 URL 是公开桶 + 自定义域拼出来的**永久公开地址**（`getPublicUrl`，
非 presigned，无签名/无 expiry），重复下载不会过期。

## 文件清单

| 文件 | 改动 |
|---|---|
| `core-api/.../modules/ai/service/AiApiKeyStore.kt` | `pick()` 全冷却跳跃；新增 `disableKeyFn` seam + `disableKey()`；2026-10-09：`disableKeyFn` 改 `(String) -> Boolean`，新增 `evict()`，写库成功即本地剔除 |
| `core-api/.../modules/ai/service/SpringAiScanRunner.kt` | `KeyFailure` data class；`classify` 返回类型；catch 分支调 `disableKey` + 日志分级；2026-10-10：新增 `isTransientImageFetchFailure` + `MAX_IMAGE_FETCH_ATTEMPTS=2`（400/422 的两类语义，见上节） |
| `core-api/.../modules/ai/service/AiConfig.kt` | 组装 `disableKeyFn`（`createUpdate().execute()` 局部更新 + `catch (e: Exception)` 收敛）；`warnDegraded` 消息参数化；新增 `id` 扩展属性与 `UUID` import；顺带删除未使用的 `aiApiKeyRepo` 注入（死代码，已验证无引用）；2026-10-09：`disableKeyFn` 返回 Boolean（写成功/已停用 = true，异常 = false）；2026-10-10：补 `set(updatedAt, Instant.now())` + `where(enabled eq true)` |
| `core-api/src/test/.../modules/ai/AiApiKeyStoreTest.kt` | Fake 提供 `disableKeyFn`；新增全冷却跳跃 / `probe=1` 退化用例；2026-10-09：Fake 支持 `disableSucceeds`，新增「禁用后立即不再被 pick」「写失败不剔除」「剔除到空触发重载」3 个用例 |
| `core-api/src/test/.../modules/ai/SpringAiScanRunnerClassifyTest.kt` | 断言从 `Pair` 改 `KeyFailure`；新增类型化 vs 兜底 `typedInvalidKey` 用例 |
| `core-api/src/test/.../modules/ai/SpringAiScanRunnerDisableTest.kt`（新增） | mock `ChatClient.prompt().call()` 抛 `UnauthorizedException` → 断言 `disableKey` 被调；message 兜底异常 → 只 `markCooldown` 不禁用；`disableKeyFn` 抛任意异常 → 主流程不中断（runner 侧对 seam 返回值无依赖，天然不中断） |
| `core-api/src/test/.../modules/ai/AiConfigDisableKeyFnTest.kt`（新增） | 纯单测（stub `KSqlClient`，不依赖 DB）：更新抛 `RuntimeException` / `DataAccessException` / `IllegalArgumentException` → `disableKey` 均不外抛（锁「任何异常不逃出 disableKeyFn」不变量）；正常返回 affected 计数 |
| `core-api/src/test/.../modules/ai/AiConfigDisableKeyDbTest.kt`（新增） | 真库（沿用 `CustomerScanMetricsRepositoryDbTest` 的 PG+Flyway+`newKSqlClient` 模式）插入 enabled=true 行 → `createUpdate` → 断言 enabled=false；0 行场景；2026-10-10：新增「禁用 bump updated_at」「重复禁用不动 updated_at」2 个用例（后者锁定 `enabled eq true` 带来的幂等性） |
| `core-api/src/test/.../modules/ai/SpringAiScanRunnerImageFetchRetryTest.kt`（新增，2026-10-10） | 图片拉取失败重试后成功（且 key 未被冷却/禁用）／到上界后放弃（call 次数=2 而非 4）／真坏图片 400 一次即止／非 400 但含 media URL 短语不被劫持 |
| `docs/design/ai/api-key-pool.md` | 基线文档：仅修订记录 + 关联加指针，实质内容不并入（避免混淆） |

不改：`AiApiKeyRepository.kt`（`save`/`findById` 仍无 main 调用点；禁用走 `AiConfig` 全局 `sqlClient`）、`application.yml`、DB schema。

## 验证

1. 局部单测：`./gradlew :core-api:test --tests "*AiApiKeyStoreTest" --tests "*SpringAiScanRunner*" --tests "*AiConfigDisableKeyFnTest"`
2. DB 集成（需 Docker 或 `TEST_PG_URL`，否则 `assumeTrue` 跳过）：`./gradlew :core-api:test --tests "*AiConfigDisableKeyDbTest"`
3. 全量回归：`./gradlew :core-api:test`
4. 提交前：`node .gitnexus/run.cjs detect-changes --scope all --repo .`

## 风险

- **impact CRITICAL（`epistemic: exact`，非 UNKNOWN）**：`pick` / `markCooldown` 各 7 个上游调用方、5 条执行流（`ScanTaskService.runTask`、`DeepResearchTaskService.runTask`、`AiFacade.runAiScan` 及两条 `doRun`）。runner catch 分支被 scan 与 deep-research 的同步/异步路径共用——**禁用逻辑的任何异常都必须在 `AiConfig.disableKeyFn` 内吞掉**（`catch (e: Exception)` 整体收敛，而非按类型列举——Jimmer 异常不经 Spring 翻译，catch `DataAccessException` 兜不住），绝不能让 DB 故障导致扫描失败。该不变量配纯单测锁定（见文件清单 `AiConfigDisableKeyFnTest`）。
- **不可逆性**：`enabled=false` 需人工恢复；`loadKeys` 不再加载禁用 key，`probe`/重试逻辑感知不到它。已由三层误杀控制限制。
- 现有 9 个 `AiApiKeyStoreTest` 中命中路径断言（游标 +1 轮询、`cursorSeed` 确定性覆盖环形回绕）不受跳跃改动影响（仅全冷却才触发跳跃）。
- 现有 6 个 `SpringAiScanRunnerClassifyTest` 断言形式从 `Pair` 改 `KeyFailure`，需同步更新。

## 已知取舍

- **message 兜底命中的真失效 key 只冷却不禁用**：该 key 每 1h 回池一次烧最多 4 次尝试，直到下次被类型化异常命中才禁用。换取的是不误杀好 key——与 `classify` 删除纯数字匹配同源的风险权衡。
- **跨实例的陈旧窗口仍靠 Redis 冷却覆盖**：本实例写成功后立即剔除；其他实例（若有）各自缓存，最长 300s 后重载剔除，这段窗口由 Redis 1h 冷却覆盖（1h > 300s）。**不引入 pub/sub 广播**：为这段窗口引入通知机制的成本高于收益（Redis 冷却已经能兜，且当前生产是单实例），属于 YAGNI。多实例部署时要意识到这条。
- **不引入禁用阈值/开关配置**：一次类型化 401 即禁用，行为简单可预测；阈值化（如连续 N 次）属 YAGNI，且会引入跨 attempt/请求的计数状态。
- **provider 鉴权故障会级联禁用整个池**：鉴权服务故障 / 网关误配 / 本机 IP 被封时，全池 key 会被类型化 401/403 逐个永久禁用。影响自限（此时池子本就不可用，扫描同样在失败）；ERROR 总量以池大小为上界（每 key 至多一次禁用 + 一条日志，~3000 条）；恢复是一次 `UPDATE core_ai_apikey SET enabled=true`（或按 updatedAt 圈定误杀时段的行）。接受此风险换取实现简单，不加熔断/阈值。

## 关联

- 基线（轮询 + 打乱 + Redis 冷却 + 降级）：[api-key-pool.md](api-key-pool.md)
- 表结构（`core_ai_apikey` + `provider` 列）：见 [api-key-pool.md](api-key-pool.md)「表结构」一节
- 实体：`entity/ai/AiApiKey.kt`（`enabled` / `unavailableUntil`）
- 生产排查手册（key 池 SQL / 日志怎么读 / 误杀恢复 / 坑）：
  [../../ops/issues/2026-10-09-ai-api-key-invalid-disabling.md](../../ops/issues/2026-10-09-ai-api-key-invalid-disabling.md)
  （通用的 ssh/连库/日志位置见 [../../ops/PRODUCTION_ACCESS.md](../../ops/PRODUCTION_ACCESS.md)）

## 修订记录

- 2026-10-10（两项，均源自生产现象）：**① 禁用补写 `updated_at`** + where 收紧 `enabled eq true`。前者是因为 `core_ai_apikey` 无 ON UPDATE trigger、Jimmer 局部更新只写 set 过的列，导致自动禁用不留时间痕迹（2026-10-09 排查时 `updated_at` 完全圈不出禁用时间点）；后者是补 `updated_at` 的连带要求——否则每次重复禁用都 affected=1，时间戳会漂移到最后一次重试、并打出似是而非的 WARN。**② 图片拉取失败从「不可重试」改为有限重试**（见「400/422 的两类语义」一节）：provider 回源下载超时被它自己上报为 400，实际是瞬态错误；命中 `isTransientImageFetchFailure` 时换 key 重试至多 2 次、不冷却不禁用 key，耗尽才抛 `INVALID_REQUEST`（message 与真坏图片区分）。产出 `docs/ops/issues/2026-10-10-ai-image-fetch-timeout-retry.md`。
- 2026-10-09（排查 `2026-10-07T20:23` 那条 401 ERROR 后补）：`disableKeyFn` 返回值改 `Boolean`（写成功/已停用 = true，异常 = false），`AiApiKeyStore.disableKey` 在 true 时调新增的 `evict()` 把 key 从本进程列表**立即**剔除，不再等 300s 重载。动机：那段陈旧窗口原先只靠同一条失败链写的 Redis 1h 冷却守护，一旦冷却键丢失（生产 Redis `appendonly=no`，重启会丢）或 MGET 降级 fail-open，已禁用的 key 会被再挑出去一次。写失败时**不**剔除（Redis 冷却 + 重载仍是唯一兜底，避免让还能用的 key 白掉轮换位）。伴随产出排查记录 `docs/ops/issues/2026-10-09-ai-api-key-invalid-disabling.md` 与通用接入手册 `docs/ops/PRODUCTION_ACCESS.md`。
- 2026-10-04 按 review 修订：`disableKeyFn` 改 `catch (e: Exception)` 整体收敛（Jimmer 异常不经 Spring 翻译、非 `DataAccessException`，按类型列举兜不住，兑现「任何异常不逃出」不变量），并新增 `AiConfigDisableKeyFnTest` 纯单测锁定；`warnDegraded` 消息参数化（原硬编码 "redis failed" 会误导 DB 故障排查）、`affected=0` 降 INFO；局部更新改用项目惯例 `createUpdate().execute()`（6 处先例，弃用无先例的 `executeUpdate`）；问题二成因归因修正（shuffle 已切断导入聚集，连续冷却区段实际来自「风暴顺路打冷却」与「高冷却占比」两条路径）；已知取舍补 provider 鉴权故障级联禁用场景（恢复 = 一次 UPDATE，ERROR 总量以池大小为上界）；`SpringAiScanRunnerClassifyTest` 用例数勘误 5→6；AiConfig 顺带删除未使用的 `aiApiKeyRepo` 注入。

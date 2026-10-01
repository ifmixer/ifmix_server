# AI API Key 池：负载均衡与分布式冷却

状态：**设计定稿，待实现**（2026-10-01 按评审意见修订 + 去 agnes 化，见文末修订记录）。当前实现（`modules/ai/service/AiApiKeyStore.kt`）是本方案要替换的基线。

Provider 无关的通用 key 池逻辑；Agnes 只是当前唯一 provider（`provider=10`，表通用化见 [api-key-table](api-key-table.md)）。

## 背景与现状

AI 扫描（`m_ai_createScan` / `m_ai_runDeepResearch`）通过 `SpringAiScanRunner` 调用 AI API，key 池来自 `core_ai_api_key` 表（`enabled=true AND provider=10`，量级 ~3000，`rate_limit` 均为 -1；表已由 `core_ai_agnes_key` 通用化改名，见 [api-key-table](api-key-table.md)）。

当前选 key 流程（2026-10 重构后）：

1. key 列表在内存缓存（TTL 300s，`AiApiKeyStore.current()`），冷却状态随缓存跨请求保留；
2. `weightedPick` 实为 **first-available**：遍历状态表取第一个未冷却的 key——所有流量集中打到同一 key，直到它被冷却或重载；
3. 冷却（429→300s / 超时→60s / 其他→30s）存**实例内存**；
4. TTL 重载时整个状态表清零重建——刚被冷却的 key 可能立即再被选中；
5. `StringRedisTemplate` 已注入但未使用（预留）；`app.agnes.scanKeyRateLimit` 与 `preDeductPerAttempt` 配置无代码绑定（死配置，已随去 agnes 化删除）。

问题：无负载均衡（单 key 热点）、冷却非分布式（多实例各自为政）、重载清零冷却。

## 目标

1. **负载均衡**：流量在 key 池上均匀轮转，故障 key 被冷却后自然滑过；
2. **真实冷却**：跨实例共享、进程重启不丢、TTL 重载不清零、自动过期解冻；
3. 复用既有护栏：`MAX_ATTEMPTS_PER_MODEL=4` 重试上界、SDK `maxRetries=0`、client 缓存、120s 超时——均不变。

## 总体设计

| 状态 | 位置 | 理由 |
|------|------|------|
| key 列表（id/key，加载时 **shuffled()**） | 内存，300s TTL 重载 | 变更频率低（仅导入脚本写库），不必每请求查库 |
| 冷却标记 | Redis `SET + EX` | 自带过期=自动解冻；跨实例共享；重载/重启不清零 |
| 轮询游标 | 实例内存 `AtomicLong`，**初值随机** | 不需要全局一致；各实例独立轮询 |

不引入 per-key 配额计数（见「明确不做」第 1 条），Redis 只承担冷却一种状态。

### Redis 数据结构（带 hash tag）

```
aikey:{cd}:{keyId}  → "429" | "timeout" | "5xx" | "401" | "403"   # SETEX = 冷却秒数；key 存在即冷却中
```

- 冷却原子性由 `SETEX` 保证，过期即解冻，**无需清理任务**。
- hash tag `{cd}` 把所有冷却 key 收进同一 slot：Redis **Cluster** 下 `MGET` 才不会报 `CROSSSLOT`（当前部署为单机/哨兵，Cluster 是预留兼容）。冷却 key 体量小（≤3000 个短字符串），单 slot 无压力。
- 前缀 `aikey`（provider 无关）；将来如需按 provider 隔离冷却，扩展为 `aikey:{cd}:{provider}:{keyId}` 即可，结构不变。

## 选取与重试循环

```
pick():                                            # 每请求 1 次 MGET
  n      = keys.size                               # ~3000
  probe  = min(8, n)                               # 单次探测窗口
  start  = cursor.getAndIncrement() mod n          # 游标推进，负载在 key 池上滑动
  cands  = keys[start, start+probe)                # 环形取；列表加载时已打乱
  cooled = redis.MGET(cands 的 aikey:{cd}:*)       # Redis 异常 → 视为全部未冷却（见降级）
  return cands 中第一个未冷却者，或 null            # 窗口内全冷却

run(ctx, input):                                   # SpringAiScanRunner
  for model in allModels:                          # defaultModel + fallbackOrder
    attempts = 0
    while attempts < MAX_ATTEMPTS_PER_MODEL:       # = 4，与 key 池大小解耦
      key = pick()
      if key == null:
          attempts++                                # null 也算一次 attempt，continue——
          continue                                  # 冷却按 key 不分模型，换模型用的是同一批 key，
                                                    # 游标已推进，下一轮自然换窗口
      try:
          调用 + JSON 解析
          return 结果
      catch e:
          按 e 分类冷却（下表）；解析失败不冷却（见冷却策略备注）
  # 所有模型耗尽 → AI_UNAVAILABLE
```

负载效果：游标每请求 +1 且初值随机、列表已打乱，流量在 3000 个 key 上均匀滑动；某 key 冷却后游标自然越过。导入脚本按账号配对入库、同账号 key 相邻且可能一起 429——**加载时 `shuffled()`** 切断相邻性，避免"一段连续冷却后的可用 key 接住整段流量"。

## 冷却策略

| 失败类型 | 判定 | 冷却 | Redis value |
|---|---|---|---|
| 429 / rate limit | `RateLimitException`（类型判定优先，message 兜底） | 300s | `"429"` |
| 401 / 403（key 失效） | `UnauthorizedException` / `PermissionDeniedException` | **1h** | `"401"` / `"403"` |
| 超时 | `OpenAIIoException` + cause 为 Socket/Timeout（沿用 `isTimeoutException`） | 60s | `"timeout"` |
| 5xx 及其他调用失败 | `InternalServerException` 等 | 30s | `"5xx"` |
| **JSON 解析失败** | `parseJsonToMap` 抛出 | **不冷却** | — |

- 401/403 是 key 失效，30s 冷却会让失效 key 周期性占掉重试名额——拉长到 1h 并打 **ERROR**（带 keyId），提示运营从 key 表 `enabled=false` 清理。判定用 openai-java 4.39 的类型化异常（`com.openai.errors.*`），message 匹配仅作兜底。
- **解析失败不冷却是已知取舍**：坏 JSON 是模型输出问题，不是 key 的问题。轮询游标每次 attempt 都推进，下一轮自然换 key，不会在坏输出上重复烧同一个 key。

## 降级（Redis 故障）

- Redis 操作整体 `try/catch (RedisConnectionFailureException | DataAccessException)`：异常时**视为全部未冷却**放行 + WARN（按实例限频告警，避免刷屏）。
- **不设内存兜底层**：坏 key 在降级窗口内最多被重试 `MAX_ATTEMPTS_PER_MODEL=4` 次，上界已存在，内存态收益为零。
- 注意：**不要**复制现有 `RateLimiter` 的写法——它是"判 `INCR` 返回 null 则放行"（`RateLimiter.kt:33`），但连接断开时 Spring 抛的是 `RedisConnectionFailureException`，不会返回 null，判空兜不住。

## 配置（统一在 `app.ai` 下，provider 无关）

```yaml
app:
  ai:
    model-fallback-order: ""
    prompt-version: ${SCAN_PROMPT_VERSION:v10}
    call-timeout-sec: ${AI_CALL_TIMEOUT_SEC:120}
    key-pool:                      # 本方案新增（通用 key 池逻辑）
      probe-window: 8              # 单次 pick 的探测窗口
      cooldown:
        rate-limited-sec: 300
        invalid-key-sec: 3600      # 401/403
        timeout-sec: 60
        other-sec: 30
# 旧 app.agnes.scanKeyRateLimit / app.agnes.ai.preDeductPerAttempt 已删除（从未接线；决策见「明确不做」）
```

## 改动清单

| 文件 | 改动 |
|---|---|
| `AiApiKeyStore.kt` | 重构：key 列表 shuffled + 游标（随机初值）+ `pick()`；Redis 读写抽成**构造函数 lambda**（与 `loadKeys` 同模式）：`readCooldowns: (List<String>) -> List<Boolean>` / `markCooldown: (keyId, sec, reason) -> Unit`，由 `AiConfig` 用 `StringRedisTemplate` 组装（异常→放行降级写在这里）；删除 `preDeduct` / `release` / `used` 计数 / `weightedPick` |
| `SpringAiScanRunner.kt` | `pick() == null` 计为一次 attempt 并 continue（不再 break 换模型）；异常分类改按 openai-java 类型化异常；401/403 → 1h + ERROR；解析失败不冷却 |
| `AiConfig.kt` | 组装两个 Redis lambda（`StringRedisTemplate`） |
| `application.yml` | 上述 `app.ai.key-pool` 配置段 |
| 测试 | 纯逻辑单测（内存 fake lambda）：轮询均匀性与打乱、游标随机初值、窗口全冷却 → attempt 语义、401/403 冷却时长、解析失败不冷却 |

## 明确不做

1. **per-key 配额计数**：不启用 per-key 限流（原 `perKeyMaxAttempts=5/3600s` 会给整池加 3000×5/h 的新上限，且失败调用也计数；Agnes 无明确单 key 合同配额，429 冷却 + 重试上界已承担实际限流）。将来确需短窗口限流，复用 `RateLimiter.checkFixedWindow(subject, limit, windowSec)`，不另写 INCR。
2. **内存兜底降级层**：Redis 故障直接放行，理由见降级一节。
3. 按 `rateLimit` 加权（当前全为 -1，`preDeduct`/`release` 恒空转，随本次一并删除；DB 列保留不动）。
4. 按 key 的 `models` 字段过滤模型（模型顺序来自全局配置 `model-fallback-order`）。
5. 全局一致游标（过度设计）。
6. 配额持久化审计、清理任务（Redis TTL 自过期，无需）。

## 已知取舍

- JSON 解析失败不冷却（见冷却策略备注）。
- 降级窗口内冷却完全失效，坏 key 最多浪费 4 次尝试/模型——上界可控。
- hash tag 使全部冷却 key 落在 Cluster 单一 slot——量级（≤3000 个短串）下无压力。

## 修订记录

- 2026-10-01 按评审意见修订：降级语义改为 catch 异常放行（原"沿用 RateLimiter 模式"描述失实，且判空兜不住连接异常）；`pick()` null 改为计 attempt + continue（原 break 换模型无意义——冷却不分模型）；新增 401/403 识别与 1h 冷却 + ERROR；key 列表加载打乱；游标初值随机；Redis key 加 hash tag 兼容 Cluster；删除 `scanKeyRateLimit`/`preDeduct`/`release`/内存兜底层/`KeyStateStore` 抽象。
- 2026-10-01 去 agnes 化：Kotlin 侧 `AgnesKey`→`AiApiKey`、`AgnesKeyStore`→`AiApiKeyStore`、`AgnesChatClientFactory`→`AiChatClientFactory`（已完成）；配置统一至 `app.ai.*`（`app.agnes.*` 已删除）；本文档改名 `ai-api-key-pool.md`，Redis 前缀改 `aikey:{cd}:`。

## 关联

- 表通用化（改名 `core_ai_api_key` + `provider` 列）：[api-key-table](api-key-table.md)
- key 导入与配对（Agnes provider）：[agnes-key-import](../scripts/agnes-key-import.md)
- 实体：`entity/ai/AiApiKey.kt`（`AiApiKeyTypes`：10=PERSONAL / 20=ENTERPRISE）

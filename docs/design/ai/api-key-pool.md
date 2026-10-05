# AI API Key 池：负载均衡与分布式冷却

状态：**已实现（2026-10-01）**——`AiApiKeyStore`（轮询 + 打乱 + 随机游标 + Redis 冷却 lambda）、`SpringAiScanRunner`（null=attempt、类型化异常分类、401/403 长冷却、解析失败不冷却）、`AiConfig`（Redis lambda 组装 + 降级放行）、`app.ai.key-pool` 配置、单测（`AiApiKeyStoreTest`，7 个分支）。

Provider 无关的通用 key 池逻辑；Agnes 只是当前唯一 provider（`provider=10`，表结构见下「表结构」）。

## 背景与现状

AI 扫描（`m_ai_createScan` / `m_ai_runDeepResearch`）通过 `SpringAiScanRunner` 调用 AI API，key 池来自 `core_ai_api_key` 表（`enabled=true AND provider=10`，量级 ~3000，`rate_limit` 均为 -1）。

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
3. 复用既有护栏：`MAX_ATTEMPTS_PER_MODEL=4` 重试上界、SDK `maxRetries=0`、client 缓存——均不变。调用超时按线上实况（最长 ~5min、常 2-3min）设 360s 覆盖长尾。

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
pick():                                            # 每次 attempt 1 次 MGET（最坏 = 模型数 × 4）
  n      = keys.size                               # ~3000
  probe  = min(5, n)，clamp ≥ 1                    # 单次探测窗口（误配 0/负数时 clamp，否则 pick 恒空）
  start  = cursor.getAndIncrement() mod n          # 游标推进，负载在 key 池上滑动
  cands  = keys[start, start+probe)                # 环形取；列表加载时已打乱
  cooled = redis.MGET(cands 的 aikey:{cd}:*)       # Redis 异常 → 视为全部未冷却（见降级）
  return cands 中第一个未冷却者，或 null            # 窗口内全冷却

run(ctx, input):                                   # SpringAiScanRunner
  deadline = now + scan-deadline-sec(600)          # 总预算：扫描在 GraphQL 请求内同步执行
  for model in allModels:                          # defaultModel + fallbackOrder
    attempts = 0
    while attempts < MAX_ATTEMPTS_PER_MODEL:       # = 4，与 key 池大小解耦
      if now + call-timeout >= deadline: throw AI_UNAVAILABLE
                                                   # 剩余时间不够一次完整调用就不发起新 attempt
                                                   # → 在途调用都能在预算内结束（最坏总耗时 = deadline）
      key = pick()
      if key == null:
          attempts++                                # null 也算一次 attempt，continue——
          continue                                  # 冷却按 key 不分模型，换模型用的是同一批 key，
                                                    # 游标已推进，下一轮自然换窗口
      try:
          调用 + JSON 解析
          return 结果
      catch e:
          (sec, reason) = classify(e)               # 按 e 分类冷却（下表）；解析失败不冷却（见冷却策略备注）
  # 所有模型耗尽或超预算 → AI_UNAVAILABLE
```

负载效果：游标每请求 +1 且初值随机、列表已打乱，流量在 3000 个 key 上均匀滑动；某 key 冷却后游标自然越过。导入脚本按账号配对入库、同账号 key 相邻且可能一起 429——**加载时 `shuffled()`** 切断相邻性，避免"一段连续冷却后的可用 key 接住整段流量"。

## 冷却策略

| 失败类型 | 判定 | 冷却 | Redis value |
|---|---|---|---|
| 429 / rate limit | cause 链含 `RateLimitException`（message 兜底） | 300s | `"429"` |
| 401 / 403（key 失效） | cause 链含 `UnauthorizedException` / `PermissionDeniedException` | **1h** | `"401"` / `"403"` |
| 超时 | cause 链含 `SocketTimeoutException`/`TimeoutException`（message 兜底） | 300s | `"timeout"` |
| 5xx 及其他调用失败 | 其余异常（含 400、网络错误等） | 30s | `"error"` |
| **JSON 解析失败** | `parseJsonToMap` 抛出 | **不冷却** | — |

- **所有判定沿 cause 链下探**（`generateSequence(e) { it.cause }`，深度上限 16）：调用走 Spring AI 的 ChatClient，openai-java 的类型化异常可能被包装后再抛出，只看最外层会漏判。类型优先，message 兜底。
- 401/403 是 key 失效，30s 冷却会让失效 key 周期性占掉重试名额——拉长到 1h 并打 **ERROR**（带 keyId），提示运营从 key 表 `enabled=false` 清理。
- **解析失败不冷却是已知取舍**：坏 JSON 是模型输出问题，不是 key 的问题。轮询游标每次 attempt 都推进，下一轮自然换 key，不会在坏输出上重复烧同一个 key。

## 降级（Redis 故障）

- Redis 操作整体 `try/catch (RedisConnectionFailureException | DataAccessException)`：异常时**视为全部未冷却**放行 + WARN（按实例限频告警，避免刷屏）。
- **Redis 命令超时** `spring.data.redis.timeout: 500ms`（全局配置）：Lettuce 默认 60s——Redis「卡住但不断开」时每次 MGET 都要等满 60s 才进 catch，500ms 快速失败。全局影响面：key 池（降级放行）、`RateLimiter`（限流抛错，与现状一致但更快）、`CacheAside`（缓存快速失败）——均为可接受行为。
- **不设内存兜底层**：坏 key 在降级窗口内最多被重试 `MAX_ATTEMPTS_PER_MODEL=4` 次，上界已存在，内存态收益为零。
- 注意：**不要**复制现有 `RateLimiter` 的写法——它是"判 `INCR` 返回 null 则放行"（`RateLimiter.kt:33`），但连接断开时 Spring 抛的是 `RedisConnectionFailureException`，不会返回 null，判空兜不住。

## 配置（统一在 `app.ai` 下，provider 无关）

```yaml
app:
  ai:
    model-fallback-order: ""
    prompt-version: ${SCAN_PROMPT_VERSION:v10}
    # 调用超时：线上最长 ~5min、常 2-3min，6min 覆盖长尾
    call-timeout-sec: ${AI_CALL_TIMEOUT_SEC:360}
    # 单次扫描总预算（秒，跨模型/attempt 的墙钟上限）：扫描在 GraphQL 请求内同步执行。
    # 剩余时间不足一次 call-timeout 时不发起新 attempt → 在途调用都能在预算内结束；
    # 注意网关/客户端超时通常更短，客户端应据此设置自身超时
    scan-deadline-sec: ${AI_SCAN_DEADLINE_SEC:600}
    apikey-pool:                   # 通用 key 池逻辑（provider 无关）
      probe-window: 5              # 单次 pick 的探测窗口（存储侧 clamp 下限 1）
      cooldown:
        rate-limited-sec: 300
        invalid-key-sec: 3600      # 401/403
        timeout-sec: 300           # 与典型调用时长（2-3min）对齐，避免超时 key 很快回池
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
- **403 冷却按 key 不分模型**：若 provider 用 403 表示「无权使用该模型」或地区限制（而非 key 失效），该 key 会在所有模型上停用 1h——Agnes 单 provider 阶段可接受；将来多 provider / 多模型计费时，冷却维度应细化为 (provider, key, model)。
- **超时一次即结束（默认 600/360 下）**：deadline 检查要求剩余时间 ≥ 一次 call-timeout，首次超时后（360s）剩余预算已不足以再发起一次完整调用——换 key 重试只对快速失败（429/401/5xx，240s 内）生效。要让超时也能换 key 重试，需把 `scan-deadline-sec` 提到 `call-timeout-sec` 的 2 倍以上（≥720s），代价是同步请求最长 12min、客户端早已断开。当前同步设计下选择前者；扫描改异步后可重估。
- 降级窗口内冷却完全失效，坏 key 最多浪费 4 次尝试/模型——上界可控。
- hash tag 使全部冷却 key 落在 Cluster 单一 slot——量级（≤3000 个短串）下无压力。

## 修订记录

- 2026-10-01 按评审意见修订：降级语义改为 catch 异常放行（原"沿用 RateLimiter 模式"描述失实，且判空兜不住连接异常）；`pick()` null 改为计 attempt + continue（原 break 换模型无意义——冷却不分模型）；新增 401/403 识别与 1h 冷却 + ERROR；key 列表加载打乱；游标初值随机；Redis key 加 hash tag 兼容 Cluster；删除 `scanKeyRateLimit`/`preDeduct`/`release`/内存兜底层/`KeyStateStore` 抽象。
- 2026-10-01 去 agnes 化：Kotlin 侧 `AgnesKey`→`AiApiKey`、`AgnesKeyStore`→`AiApiKeyStore`、`AgnesChatClientFactory`→`AiChatClientFactory`（已完成）；配置统一至 `app.ai.*`（`app.agnes.*` 已删除）；本文档改名 `ai-api-key-pool.md`，Redis 前缀改 `aikey:{cd}:`。
- 2026-10-01 实现：本方案落地，状态改为「已实现」。配套单测 `AiApiKeyStoreTest`（轮询覆盖、打乱不丢 key、冷却跳过与 reason、全冷却返回 null、探测窗口边界、空池、TTL 缓存）。
- 2026-10-01 二次评审修订：异常判定改为 **cause 链遍历**（Spring AI 可能包装 SDK 异常，只看最外层会漏判；深度上限 16）；文档超时判定行与实现对齐（cause 链含 Socket/Timeout 类型）；`cursorSeed` 构造参数可注入（单测确定性，覆盖环形回绕）；403 跨模型冷却写入已知取舍；删除 `application-local.yml` 残留的 `app.agnes.ai.modelFallbackOrder`；降级告警限频改用 `AtomicLong` CAS；文档 MGET 次数措辞修正（每次 attempt 一次，最坏 = 模型数 × 4）。
- 2026-10-01 阈值与命名调整：配置段 `app.ai.key-pool` → **`app.ai.apikey-pool`**；`probe-window` 默认 8 → **5**，存储侧 clamp 下限 1（误配 0/负数不再导致 pick 恒空，配套单测）；`call-timeout-sec` 120 → **360**（线上最长 ~5min、常 2-3min，6min 覆盖长尾）；超时冷却 60 → **300**（与典型调用时长对齐，避免超时 key 很快回池）。
- 2026-10-01 三次评审修订：新增 **`app.ai.scan-deadline-sec`（默认 600s）总预算**——扫描在 GraphQL 请求内同步执行，连续超时最坏 4×6min×模型数，超预算抛 AI_UNAVAILABLE（`classify` 抽为 companion 纯函数并补单测）；`spring.data.redis.timeout: 500ms`（Lettuce 默认 60s 会让卡住的 Redis 每次 MGET 等满 60s；全局影响面 = key 池降级 / RateLimiter 快速抛错 / CacheAside 快速失败）；401/403 的 message 兜底**去掉纯数字匹配**（request id "40312" 会误判成 key 失效被冷却 1h，只留类型化异常 + 短语）；冷却原因 `"5xx"` → **`"error"`**（else 分支也接住 400/网络错误，标 5xx 误导排查）；`probeWindow` 去掉 store 默认值（由 AiConfig 显式注入，默认值只在 yml/@Value 一处）；测试文件挪到与包名一致的目录。
- 2026-10-01 四次评审修订：`"429" in m` 纯数字匹配删除（与 401/403 同类问题，request id "42917" 会误判限流，配套单测）；deadline 检查改为「剩余时间不足一次 call-timeout 时不发起新 attempt」（`now + callTimeout >= deadline` 即放弃——否则实际最坏耗时是 deadline + 一次调用超时 ≈ 960s，且网关/客户端超时通常更短）；KDoc 过期的「超时→60s」改为引用配置；deadline 日志字段 `modelsTried` → `model`。
- 2026-10-01 五次评审修订：runner `init` 增加 **`scanDeadlineSec > callTimeoutSec` 启动校验**（deadline ≤ call-timeout 时每次扫描都会在首试前被拦下、静默全量 AI_UNAVAILABLE，现在启动即失败）+ 配套单测；确定取舍：默认 600/360 下**超时一次即结束**，换 key 重试仅对快速失败生效，写入「已知取舍」（扫描改异步后可重估，届时把 deadline 提到 call-timeout 的 2 倍以上）。
- 2026-10-04 演进（**已实现**）：[401/403 永久禁用 + pick 全冷却跳窗口](api-key-disable-and-probe-skip.md)——坏 key 只冷却不禁用导致 1h 后自动回池周期性重烧；全冷却时游标推进 1、探测窗口重叠 4/5，局部连续冷却区卡掉整请求。独立成文，本文不重复实现细节。

## 表结构（`core_ai_api_key`，V8 通用化，已完成）

- `core_ai_agnes_key`（Agnes 专用）于 V8 改名为通用表 `core_ai_api_key`，新增 `provider smallint DEFAULT 10 NOT NULL`（码表 `ApiProviders`，10=AGNES；独立文件放 `entity/ai/`——Jimmer KSP 坑：`object` 与 `@Entity` 同文件会静默跳过实体生成），索引 `agnes_key_uq` → `api_key_uq`。
- `type` 列（10=PERSONAL / 20=ENTERPRISE，码表 `AiApiKeyTypes`）是 key 自身类型，与 `provider` 正交。实体 `entity/ai/AiApiKey.kt`（全局级，继承 `BaseEntity`）。
- V8 对已有环境是原地改名（数据自然迁移），V1 不动（checksum + 3169 条 INSERT）；新环境 V1 旧名建表 → V8 改名，两条路径终态一致。导入脚本 `scripts/agnes_keys/import_from_register.sh` 已切换目标表（并修复其仍写 V2 前旧表名的潜伏 bug）。
- 已知遗留：主键索引名仍为 `agnes_key_pkey`（纯命名，不值得单独迁移）。
- 将来接新 provider：key 格式校验/导入配对规则在导入脚本层处理，表结构不动；key-pool Redis 前缀可加 provider 维度。

## 关联

- key 导入与配对（Agnes provider）：[agnes-key-import](agnes-key-import.md)
- 实体：`entity/ai/AiApiKey.kt`
- 演进（401/403 永久禁用 + pick 全冷却跳窗口）：[api-key-disable-and-probe-skip](api-key-disable-and-probe-skip.md)

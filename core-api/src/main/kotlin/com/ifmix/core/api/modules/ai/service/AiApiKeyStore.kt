package com.ifmix.core.api.modules.ai.service

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * AI API Key 池（provider 无关的通用逻辑）：轮询选取 + 分布式冷却。
 *
 * 设计（docs/design/ai/api-key-pool.md）：
 * - key 列表内存缓存（TTL 内复用），加载时打乱（shuffled）切断导入数据按账号聚集的相邻热点；
 * - 冷却状态在 Redis（SET + EX，自带过期解冻），跨实例共享、重载/重启不清零；
 * - 选取为 round-robin + 探测窗口：游标每请求 +1（初值随机），一次批量读探测 probe 个候选，
 *   返回第一个未冷却者；候选全冷却返回 null（调用方计为一次 attempt，游标已推进）；
 * - Redis 读写经构造函数 lambda 注入（与 loadKeys 同模式），由 AiConfig 用 StringRedisTemplate
 *   组装并统一做「异常 → 视为未冷却放行 + 限频 WARN」降级——不设内存兜底层，
 *   坏 key 最多消耗 MAX_ATTEMPTS_PER_MODEL 次尝试，上界已存在。
 */
class AiApiKeyStore(
    private val loadKeys: () -> List<AiApiKeyDoc>,
    private val readCooldowns: (List<String>) -> List<Boolean>,
    private val markCooldownFn: (keyId: String, cooldownSec: Long, reason: String) -> Unit,
    /**
     * 失效 key 的永久禁用（PG enabled=false，不可逆，需人工恢复）；由 AiConfig 用全局 sqlClient 组装，异常在组装侧收敛。
     * 返回是否**已确认该 key 不在 enabled 集合**——写库成功（affected>0，或已是 disabled/不存在）返回 true，
     * 写失败（异常在 AiConfig 内吞掉）返回 false；只有 true 才触发本地立即剔除（见 [disableKey]）。
     */
    private val disableKeyFn: (keyId: String) -> Boolean,
    /** 单次 pick 的探测窗口（无默认值，由 AiConfig 从 app.ai.apikey-pool.probe-window 显式注入）；下限 1（误配 0/负数时 clamp，否则 pick 恒空）。 */
    probeWindow: Int,
    /** 轮询游标初值；默认随机，单测传固定值获得确定性（负数由 floorMod 归一）。 */
    cursorSeed: Long = ThreadLocalRandom.current().nextLong(),
) {

    private val effectiveProbeWindow = probeWindow.coerceAtLeast(1)

    data class AiApiKeyDoc(
        val id: String,
        val key: String,
    )

    companion object {
        private val log = LoggerFactory.getLogger(AiApiKeyStore::class.java)

        /** key 列表刷新间隔；冷却状态在 Redis，重载不影响。 */
        private const val REFRESH_TTL_SEC = 300L
    }

    @Volatile private var keys: List<AiApiKeyDoc> = emptyList()
    @Volatile private var loadedAt: Instant = Instant.EPOCH

    /** 轮询游标，初值随机（可注入固定值供单测）——多实例同批启动时避免同步打同一批 key。 */
    private val cursor = AtomicLong(cursorSeed)

    /** 当前 key 列表（已打乱；TTL 过期或为空时从 DB 重载并重新打乱）。 */
    fun currentKeys(): List<AiApiKeyDoc> {
        cached()?.let { return it }
        synchronized(this) {
            cached()?.let { return it }
            val fresh = loadKeys().shuffled()
            keys = fresh
            loadedAt = Instant.now()
            log.info("AI API key pool loaded. count={}", fresh.size)
            return fresh
        }
    }

    fun isEmpty(): Boolean = currentKeys().isEmpty()

    /**
     * 轮询选一个未冷却的 key。
     * 每调用游标净 +1（命中语义不变），从游标处环形取 probe 个候选，一次批量读冷却状态，
     * 返回第一个未冷却者；候选全冷却返回 null 并额外推进 (probe-1)（净推进 probe）——
     * 连续冷却区段下每次 attempt 跳过整段窗口，而非以下一窗起点重叠 4/5 反复重探同批冷却 key
     * （probe=1 时额外 +0，退化为原行为；原子相对增量，并发下跳跃只增不减）。
     */
    fun pick(): AiApiKeyDoc? {
        val ks = currentKeys()
        if (ks.isEmpty()) return null
        val n = ks.size
        val probe = minOf(effectiveProbeWindow, n)
        val start = Math.floorMod(cursor.getAndIncrement(), n.toLong()).toInt()
        val candidates = List(probe) { ks[(start + it) % n] }
        val cooled = readCooldowns(candidates.map { it.id })
        val hit = candidates.asSequence()
            .zip(cooled.asSequence())
            .firstOrNull { !it.second }
            ?.first
        if (hit == null && probe > 1) {
            cursor.addAndGet((probe - 1).toLong())
        }
        return hit
    }

    /** 标记 key 冷却（秒）。reason 记录冷却原因（"429"/"401"/"403"/"timeout"/"error"），便于排查。 */
    fun markCooldown(keyId: String, cooldownSec: Long, reason: String) {
        markCooldownFn(keyId, cooldownSec, reason)
    }

    /**
     * 永久禁用 key：先写库（seam），**写成功才把它从本地列表剔除**（见 [evict]）。
     *
     * 为什么需要本地剔除：写 `enabled=false` 之后，本列表要等 [REFRESH_TTL_SEC] 到期重载才不含它，
     * 这段陈旧窗口原先只靠同一条失败链上写的 Redis 1h 冷却兜；一旦冷却没拦住（Redis 丢键/降级放行），
     * 已禁用的 key 会被再挑出去一次。写成功即剔除，把这道窗口从"≤300s"压到"本次调用之后立即"。
     *
     * 写失败时不剔除：此时 Redis 冷却 + 后续重载仍是唯一兜底，本地提前剔除会让一个其实还能用的 key
     * 白白少一个轮换位（禁用不可逆，粘性更强的一侧说了算）。
     */
    fun disableKey(keyId: String) {
        if (disableKeyFn(keyId)) evict(keyId)
    }

    /**
     * 从本地列表移除 key（不等 TTL 重载）。列表被清空时 [cached] 判空 → 下次 [currentKeys] 立即重载，
     * 重载结果由 `where enabled = true` 决定，不会因为这批剔除而误报空池。
     */
    fun evict(keyId: String) {
        synchronized(this) {
            val current = keys
            if (current.any { it.id == keyId }) {
                keys = current.filter { it.id != keyId }
                log.info("AI API key evicted from local pool. keyId={} remaining={}", keyId, keys.size)
            }
        }
    }

    private fun cached(): List<AiApiKeyDoc>? {
        val cached = keys
        val fresh = cached.isNotEmpty() &&
            Duration.between(loadedAt, Instant.now()).seconds < REFRESH_TTL_SEC
        return if (fresh) cached else null
    }
}

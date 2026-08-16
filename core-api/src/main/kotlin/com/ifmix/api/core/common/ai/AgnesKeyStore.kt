package com.ifmix.api.core.common.ai

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Agnes API key 仓储（内存 + Redis 缓存）。
 *
 * 职责：
 * - 加权随机挑选可用 key（基于 remainingQuota 权重）
 * - 预扣配额（DECR 原子操作，越限时冷却重挑）
 * - 冷却标记（markUnavailable）：设置 unavailableUntil → 窗口到期自动恢复
 * - 归还配额（release）：成功请求后消耗预扣，失败时归还
 *
 * @param redis Redis 连接（String 类型 ops）
 * @param now 当前时间工厂（测试可注入固定时间）
 * @param rng 随机数生成器（测试可注入确定性值）
 * @param loadKeys 加载启用状态的 key 列表回调（测试可注入 mock）
 */
open class AgnesKeyStore(
    private val redis: StringRedisTemplate,
    private val now: () -> Instant = { Instant.now() },
    private val rng: Random = Random(),
    private val loadKeys: () -> List<AgnesKeyDocument> = { emptyList() },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_QUOTA_PREFIX = "agnes:key:quota:"
        private const val KEY_COOLING_PREFIX = "agnes:key:cooling:"
        private const val DEFAULT_WINDOW_SEC = 86_400L
    }

    // ---- 内部数据类 ----

    /** 内存中 key 的运行时状态。 */
    data class KeyState(
        val doc: AgnesKeyDocument,
        var remaining: Long,
        var coolingUntil: Instant? = null,
    )

    // ---- 核心方法 ----

    /**
     * 初始化：从数据库加载所有启用的 key，并同步 Redis 中的配额计数。
     *
     * 首次调用时构建内存中的 KeyState 映射，后续通过 incr/decr 维护配额。
     */
    open fun init(): MutableMap<String, KeyState> {
        val docs = loadKeys()
        val states = mutableMapOf<String, KeyState>()
        for (doc in docs) {
            val keyId = doc.id.toHexString()
            val quota = doc.rateLimit.takeIf { it > 0 } ?: 999_999L
            // 从 Redis 读取当前已用计数，计算剩余
            val used = redis.opsForValue().get(buildQuotaKey(keyId))?.toLongOrNull() ?: 0L
            states[keyId] = KeyState(doc, quota - used)
        }
        log.info("AgnesKeyStore initialized with ${states.size} keys")
        return states
    }

    /**
     * 加权随机挑选一个可用的 key。
     *
     * 排除条件：
     * - remaining <= 0（配额耗尽）
     * - coolingUntil != null 且未过冷却期
     * - unavailableUntil != null 且未过窗口期
     *
     * @return 选中的 key ID，无可用 key 返回 null
     */
    open fun weightedPick(states: Map<String, KeyState>): String? {
        val candidates = states.values.filter { state ->
            val doc = state.doc
            // 检查 DB 级不可用窗口
            if (doc.unavailableUntil != null && doc.unavailableUntil!!.isAfter(now())) return@filter false
            // 检查冷却窗口
            if (state.coolingUntil != null && state.coolingUntil!!.isAfter(now())) return@filter false
            // 检查配额
            state.remaining > 0
        }

        if (candidates.isEmpty()) return null

        // 按 remaining 作为权重进行加权随机选择
        val totalWeight = candidates.sumOf { it.remaining.toDouble() }
        if (totalWeight <= 0) return null

        val target = rng.nextDouble() * totalWeight
        var cumulative = 0.0
        for (candidate in candidates) {
            cumulative += candidate.remaining
            if (target <= cumulative) {
                return candidate.doc.id.toHexString()
            }
        }
        // 浮点精度兜底：返回最后一个候选
        return candidates.last().doc.id.toHexString()
    }

    /**
     * 预扣配额：尝试为指定 key 扣除一次用量。
     *
     * 如果剩余为 0，则标记冷却并返回 false，调用方应重挑。
     *
     * @return true = 预扣成功，false = 配额耗尽（已自动标记冷却）
     */
    open fun preDeduct(states: MutableMap<String, KeyState>, keyId: String): Boolean {
        val state = states[keyId] ?: return false
        state.remaining--
        if (state.remaining < 0) {
            // 配额耗尽，标记冷却到窗口结束
            markUnavailable(states, keyId, state.doc.windowSec.takeIf { it > 0 } ?: DEFAULT_WINDOW_SEC)
            return false
        }
        return true
    }

    /**
     * 标记 key 不可用（冷却），持续到指定秒数之后。
     *
     * 同时写入 Redis 以便多实例共享冷却状态。
     */
    fun markUnavailable(states: MutableMap<String, KeyState>, keyId: String, windowSec: Long) {
        val until = now().plusSeconds(windowSec)
        states[keyId]?.coolingUntil = until
        // 在 Redis 中写入冷却标记（跨实例生效）
        redis.opsForValue().set(
            buildCoolingKey(keyId),
            until.epochSecond.toString(),
            windowSec,
            TimeUnit.SECONDS,
        )
        log.warn("Agnes key $keyId marked unavailable until $until")
    }

    /**
     * 归还预扣：扫描成功后调用，确认配额消耗。
     *
     * 如果预扣过多（remaining < 0），修正回 0。
     */
    fun release(states: MutableMap<String, KeyState>, keyId: String) {
        val state = states[keyId]
        if (state != null) {
            if (state.remaining < 0) state.remaining = 0L
        }
        // 同步到 Redis
        val quotaKey = buildQuotaKey(keyId)
        val current = redis.opsForValue().get(quotaKey)?.toLongOrNull() ?: 0L
        val ttl = state?.doc?.windowSec?.takeIf { it > 0 } ?: DEFAULT_WINDOW_SEC
        redis.opsForValue().set(quotaKey, (current + 1).toString(), ttl, TimeUnit.SECONDS)
    }

    /**
     * 释放冷却：手动恢复某个 key 的可用的状态。
     */
    fun clearCooling(states: MutableMap<String, KeyState>, keyId: String) {
        states[keyId]?.coolingUntil = null
        redis.delete(buildCoolingKey(keyId))
    }

    // ---- helpers ----

    private fun buildQuotaKey(keyId: String): String = "$KEY_QUOTA_PREFIX$keyId"

    private fun buildCoolingKey(keyId: String): String = "$KEY_COOLING_PREFIX$keyId"
}

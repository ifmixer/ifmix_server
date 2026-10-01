package com.ifmix.core.api.modules.ai.service

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * AI API Key 运行时状态管理（provider 无关的通用 key 池逻辑：冷却标记、pick）。
 *
 * 状态跨请求缓存：key 列表与冷却/计数在实例内复用（TTL 内不重载）。
 * 此前的实现每次 run() 重建状态，per-key 限流与冷却随请求结束即弃，等于没有。
 * Redis 字段预留（当前为进程内状态；多实例部署时冷却/计数需迁到 Redis，
 * 完整设计见 docs/design/ai-api-key-pool.md）。
 */
class AiApiKeyStore(
    private val redis: StringRedisTemplate,
    private val loadKeys: () -> List<AiApiKeyDoc>,
) {

    data class AiApiKeyDoc(
        val id: String,
        val key: String,
        val type: String?,
        val rateLimit: Long,
        val windowSec: Long,
        val models: String?,
    )

    data class KeyState(
        val doc: AiApiKeyDoc,
        var used: Long = 0,
        var unavailableUntil: Instant? = null,
    )

    companion object {
        /** key 列表刷新间隔：冷却状态在此期间跨请求保留。 */
        private const val REFRESH_TTL_SEC = 300L
    }

    @Volatile private var states: ConcurrentHashMap<String, KeyState>? = null
    @Volatile private var loadedAt: Instant = Instant.EPOCH

    /** 获取当前状态（跨请求缓存，TTL 过期或为空时从 DB 重载）。 */
    fun current(): ConcurrentHashMap<String, KeyState> {
        cached()?.let { return it }
        synchronized(this) {
            cached()?.let { return it }
            val fresh = ConcurrentHashMap<String, KeyState>()
            for (doc in loadKeys()) {
                fresh[doc.id] = KeyState(doc = doc)
            }
            states = fresh
            loadedAt = Instant.now()
            return fresh
        }
    }

    private fun cached(): ConcurrentHashMap<String, KeyState>? {
        val cached = states ?: return null
        val fresh = !cached.isEmpty() &&
            Duration.between(loadedAt, Instant.now()).seconds < REFRESH_TTL_SEC
        return if (fresh) cached else null
    }

    /** 选一个可用 key。简化实现：选第一个可用的（冷却中的 key 被跳过）。 */
    fun weightedPick(states: ConcurrentHashMap<String, KeyState>): String? {
        val now = Instant.now()
        return states.entries.firstOrNull { (_, state) ->
            val until = state.unavailableUntil
            until == null || now.isAfter(until)
        }?.key
    }

    /** Pre-deduct quota（乐观扣减）。返回 true = 有余量。 */
    fun preDeduct(states: ConcurrentHashMap<String, KeyState>, keyId: String): Boolean {
        val state = states[keyId] ?: return false
        if (state.doc.rateLimit < 0) return true // unlimited
        if (state.used >= state.doc.rateLimit) return false
        state.used++
        return true
    }

    /** 请求成功后确认消费。 */
    fun release(states: ConcurrentHashMap<String, KeyState>, keyId: String) {
        // Confirm usage — no-op in simplified version (quota already deducted in preDeduct)
    }

    /** 标记 key 冷却一段时间（秒）。 */
    fun markUnavailable(states: ConcurrentHashMap<String, KeyState>, keyId: String, cooldownSec: Long) {
        val state = states[keyId] ?: return
        state.unavailableUntil = Instant.now().plusSeconds(cooldownSec)
    }
}

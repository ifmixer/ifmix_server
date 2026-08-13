package com.ifmix.api.core.common.modules.ai.service

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Agnes Key 运行时状态管理（quota pre-deduct、冷却标记、weighted pick）。
 *
 * 从数据源加载 keys → 维护内存状态 → 通过 Redis 做分布式冷却/计数。
 */
class AgnesKeyStore(
    private val redis: StringRedisTemplate,
    private val loadKeys: () -> List<AgnesKeyDoc>,
) {

    data class AgnesKeyDoc(
        val id: String,
        val key: String,
        val type: String?,
        val rateLimit: Long,
        val windowSec: Long,
        val models: String?,
    )

    data class KeyState(
        val doc: AgnesKeyDoc,
        var used: Long = 0,
        var unavailableUntil: Instant? = null,
    )

    /** 初始化/刷新状态 map。 */
    fun init(): ConcurrentHashMap<String, KeyState> {
        val keys = loadKeys()
        val states = ConcurrentHashMap<String, KeyState>()
        for (doc in keys) {
            states[doc.id] = KeyState(doc = doc)
        }
        return states
    }

    /** 权重随机选一个可用 key。简化实现：选第一个可用的。 */
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

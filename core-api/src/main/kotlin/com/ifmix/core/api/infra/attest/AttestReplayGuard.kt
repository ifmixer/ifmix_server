package com.ifmix.core.api.infra.attest

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * challenge / token 一次性消费（规格 §3.1-c、§5.2）。
 *
 * `SET NX EX` 原子去重：第一次 → FIRST；已存在 → REPLAY；
 * Redis 返回 null 或抛异常 → DEGRADED（放行语义由调用方决定，规格 §4.3：可用性优先）。
 *
 * Redis 键约定（调用方拼装）：`attest:used:{SHA256(challengeStr)}`，TTL = challenge 有效期（360s）。
 *
 * DEGRADED 时打 ERROR `event=attest.redis_degraded`（节流：首次 1 条、之后每分钟最多 1 条；
 * 恢复后打 INFO `event=attest.redis_recovered`）。节流状态仅进程内（多实例各打一份，可接受）。
 */
@Component
class AttestReplayGuard(
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val throttle = DegradedLogThrottle()

    fun markUsed(key: String, ttlSec: Long): MarkResult {
        var failure: Exception? = null
        val result = try {
            when (val first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSec))) {
                null -> MarkResult.DEGRADED
                true -> MarkResult.FIRST
                false -> MarkResult.REPLAY
            }
        } catch (e: Exception) {
            failure = e
            MarkResult.DEGRADED
        }

        when (result) {
            MarkResult.DEGRADED -> {
                if (throttle.onError(System.currentTimeMillis())) {
                    log.error(
                        "event=attest.redis_degraded op=mark_used ttlSec={} error={} message={} — 一次性消费跳过，按可用性优先放行",
                        ttlSec,
                        failure?.javaClass?.simpleName ?: "null_response",
                        failure?.message,
                    )
                }
            }
            else -> {
                if (throttle.onSuccess()) {
                    log.info("event=attest.redis_recovered op=mark_used")
                }
            }
        }
        return result
    }

    enum class MarkResult {
        /** 首次写入成功（本次请求拥有该 proof 的消费权）。 */
        FIRST,

        /** 已被消费（重放）。 */
        REPLAY,

        /** Redis 故障，跳过去重（调用方按放行处理）。 */
        DEGRADED,
    }

    /**
     * 降级日志节流：首次 1 条，之后每分钟最多 1 条；恢复成功时返回 true（让调用方打 recovered）。
     * 与限流（ratelimit.degraded）的节流模式一致，但控制器独立，互不干扰。
     */
    private class DegradedLogThrottle {
        private var degraded = false
        private var lastLoggedAtMs = 0L

        @Synchronized
        fun onError(nowMs: Long): Boolean {
            val shouldLog = !degraded || nowMs - lastLoggedAtMs >= RE_LOG_INTERVAL_MS
            if (shouldLog) {
                degraded = true
                lastLoggedAtMs = nowMs
            }
            return shouldLog
        }

        @Synchronized
        fun onSuccess(): Boolean {
            val wasDegraded = degraded
            degraded = false
            return wasDegraded
        }

        companion object {
            const val RE_LOG_INTERVAL_MS = 60_000L
        }
    }
}

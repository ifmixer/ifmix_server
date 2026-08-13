package com.ifmix.api.core.common.infra.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.time.ZoneId
import com.ifmix.api.core.common.infra.http.OperationContext
import java.util.concurrent.TimeUnit.SECONDS

/**
 * UTC 日固定窗口限流器。
 *
 * 窗口按 UTC 日历日对齐（00:00:00 - 23:59:59），以 `subject`（如 clientIp）作为唯一标识。
 * 使用 Redis INCR + EXPIRE 实现原子计数。
 */
class RateLimiter(
    private val redis: StringRedisTemplate,
    private val tierResolver: TierResolver,
    private val config: RateLimitConfig,
) {

    /**
     * 检查是否超限。命中则抛 RATE_LIMITED；未命中则消耗一次配额并返回 true。
     * 同时返回当前已用次数供调试。
     */
    fun check(ctx: OperationContext, subject: String): CheckResult {
        val tier = tierResolver.resolve(ctx)
        val limit = config.limitFor(tier)
        val dayKey = utcDayKey(subject)

        val count = redis.opsForValue().increment(dayKey, 1)
        if (count == 1L) {
            // 首日首次写入，设置 TTL 到当日结束
            val ttl = secondsUntilEndOfDay()
            redis.expire(dayKey, ttl, SECONDS)
        }

        return if ((count ?: 0) >= limit) {
            CheckResult.limited(count ?: 0)
        } else {
            CheckResult.allowed(count ?: 0, limit.toLong())
        }
    }

    /**
     * 退款：当一次请求因下游错误需要"不消耗配额"时调用。
     */
    fun refund(subject: String) {
        val dayKey = utcDayKey(subject)
        redis.opsForValue().decrement(dayKey)
    }

    private fun utcDayKey(subject: String): String =
        "ratelimit:${subject}:${utcDayString()}"

    private fun utcDayString(): String =
        Instant.now().atZone(UTC).toLocalDate().toString()

    private fun secondsUntilEndOfDay(): Long {
        val now = Instant.now()
        val endOfDay = java.time.LocalDate.now(UTC)
            .plusDays(1)
            .atStartOfDay(UTC)
            .toInstant()
        return endOfDay.epochSecond - now.epochSecond
    }

    data class CheckResult(
        val allowed: Boolean,
        val count: Long,
        val limit: Long,
    ) {
        companion object {
            fun allowed(count: Long, limit: Long) = CheckResult(true, count, limit)
            fun limited(count: Long) = CheckResult(false, count, count)
        }
    }

    companion object {
        private val UTC = ZoneId.of("UTC")
    }
}

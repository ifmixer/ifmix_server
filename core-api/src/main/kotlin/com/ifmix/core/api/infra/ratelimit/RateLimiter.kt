package com.ifmix.core.api.infra.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.time.ZoneId
import com.ifmix.core.api.infra.http.OperationContext
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
        if (count == null) {
            // Redis increment 返回 null：连接异常/故障。降级放行，但必须告警——此刻限流形同虚设。
            log.warn("rate-limit degraded: redis INCR returned null, allowing request. subject={} dayKey={}", subject, dayKey)
            return CheckResult.allowed(0, limit.toLong())
        }
        if (count == 1L) {
            // 首日首次写入，设置 TTL 到当日结束
            val ttl = secondsUntilEndOfDay()
            redis.expire(dayKey, ttl, SECONDS)
        }

        return if (count > limit) {
            log.warn("rate-limit hit (utc-day): subject={} count={} limit={} tier={}", subject, count, limit, tier)
            CheckResult.limited(count)
        } else {
            CheckResult.allowed(count, limit.toLong())
        }
    }

    /**
     * 退款：当一次请求因下游错误需要"不消耗配额"时调用。
     */
    fun refund(subject: String) {
        val dayKey = utcDayKey(subject)
        redis.opsForValue().decrement(dayKey)
    }

    /**
     * 固定窗口限流（任意窗口长度）。窗口以首次 INCR 时刻起算，TTL = windowSec。
     * 命中（count > limit）返回 false（不额外退款——固定窗口下超限请求仍计入本窗口）。
     * 与 [check] 的 UTC 日窗口区分：本方法用于短窗口（如每 IP 60s N 次）。
     *
     * @param subject 唯一标识（如 clientIp）
     * @param limit   窗口内允许的最大次数
     * @param windowSec 窗口长度（秒）
     */
    fun checkFixedWindow(subject: String, limit: Int, windowSec: Long): Boolean {
        val key = "ratelimit:fw:${subject}:${windowSec}"
        val count = redis.opsForValue().increment(key, 1)
        if (count == null) {
            // Redis increment 返回 null：连接异常/故障。降级放行，但告警——此刻限流失效。
            log.warn("rate-limit degraded: redis INCR returned null, allowing request. subject={} windowSec={}", subject, windowSec)
            return true
        }
        if (count == 1L) {
            redis.expire(key, windowSec, SECONDS)
        }
        val allowed = count <= limit
        if (!allowed) {
            log.warn("rate-limit hit (fixed-window): subject={} count={} limit={} windowSec={}", subject, count, limit, windowSec)
        }
        return allowed
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
        private val log = LoggerFactory.getLogger(RateLimiter::class.java)
    }
}

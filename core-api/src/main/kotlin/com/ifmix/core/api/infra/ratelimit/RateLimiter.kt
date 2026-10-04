package com.ifmix.core.api.infra.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import com.ifmix.core.api.infra.http.ActionContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

/**
 * 限流结果（install attestation 规格 §4.6：短窗口与日窗口统一返回类型）。
 *
 * - [Allowed]：未超限，本次已计数。
 * - [Limited]：超限拒绝，`retryAfterSec` = 该 key 的 Redis 剩余 TTL
 *   （短窗口=窗口剩余秒数 → 429000；UTC 日窗口=到 UTC 零点秒数 → 429002）。
 * - [Degraded]：Redis 故障（INCR 返回 null 或抛异常）→ **放行**（可用性优先），同时打节流后的
 *   ERROR `event=ratelimit.degraded`。
 */
sealed interface RateLimitResult {
    data object Allowed : RateLimitResult
    data class Limited(val retryAfterSec: Long) : RateLimitResult
    data object Degraded : RateLimitResult
}

/**
 * 限流窗口类型。[UTC_DAY] 的 Redis key 由实现追加 `:{yyyy-MM-dd}`（UTC 时区）后缀，
 * TTL 到当天 UTC 零点；[MINUTE] 为固定 60s 窗口（以首次 INCR 时刻起算）。
 */
enum class Window { MINUTE, UTC_DAY }

/**
 * 限流器。
 *
 * 两套入口：
 * - tier 版 [check]/[refund]：UTC 日窗口 + tier 配额（仅 [RateLimiterTest] 在用，历史遗留）。
 * - 通用版 [check]：`check(window, key, limit)`，key 为**完整前缀**（含 `ratelimit:{projectId}:{action}:...`，
 *   规格 §4.6 统一格式；UTC_DAY 由实现拼日期后缀）。所有生产调用方走此入口。
 *
 * Redis 固定窗口：INCR + 首次置 TTL（原子计数，无需 Lua）。
 * Redis 故障降级：放行 + `event=ratelimit.degraded`（按 subject 前缀节流：首次必打、之后每分钟最多 1 条；
 * 恢复后第一条成功打 INFO `event=ratelimit.recovered`）。节流状态在进程内，多实例各打一份，可接受。
 * 日志只带 subject 前缀（key 去掉最后一段主体标识），**不带 IP/iid 原文**。
 */
class RateLimiter(
    private val redis: StringRedisTemplate,
    private val tierResolver: TierResolver,
    private val config: RateLimitConfig,
    /** 注入 Clock 便于测试 UTC_DAY 跨天；生产为系统 UTC 时钟。 */
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 检查是否超限（消耗一次配额）。
     *
     * @param key   完整 key 前缀，如 `ratelimit:{pid}:install:ip:min:{ip}`；UTC_DAY 时实现自动追加 `:{yyyy-MM-dd}`。
     * @param limit 窗口内允许的最大次数。
     */
    fun check(window: Window, key: String, limit: Int): RateLimitResult {
        val windowSec = when (window) {
            Window.MINUTE -> MINUTE_WINDOW_SEC
            Window.UTC_DAY -> secondsUntilEndOfDay()
        }
        val redisKey = when (window) {
            Window.MINUTE -> key
            Window.UTC_DAY -> "$key:${utcDayString()}"
        }
        val prefix = subjectPrefix(key)

        val count = try {
            redis.opsForValue().increment(redisKey, 1)
        } catch (e: Exception) {
            onDegraded(prefix, windowSec, e.javaClass.simpleName)
            return RateLimitResult.Degraded
        }
        if (count == null) {
            // Redis increment 返回 null：连接异常/故障。降级放行，但必须告警——此刻限流形同虚设。
            onDegraded(prefix, windowSec, "incr_null")
            return RateLimitResult.Degraded
        }
        onRecovered(prefix)

        if (count == 1L) {
            // 首次写入，设置窗口 TTL
            redis.expire(redisKey, windowSec, SECONDS)
        }

        if (count > limit) {
            // retryAfterSec = key 的剩余 TTL；TTL 查询失败（不应发生）退回整窗长度
            val retryAfterSec = runCatching { redis.getExpire(redisKey, SECONDS) }
                .getOrNull()?.takeIf { it > 0 } ?: windowSec
            return RateLimitResult.Limited(retryAfterSec)
        }
        return RateLimitResult.Allowed
    }

    // ===== tier 版（历史遗留，仅测试在用，行为保持不变） =====

    /**
     * 检查是否超限。命中则抛 RATE_LIMITED；未命中则消耗一次配额并返回 true。
     * 同时返回当前已用次数供调试。
     */
    fun check(ctx: ActionContext, subject: String): CheckResult {
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

    private fun utcDayKey(subject: String): String =
        "ratelimit:${subject}:${utcDayString()}"

    private fun utcDayString(): String =
        Instant.now(clock).atZone(UTC).toLocalDate().toString()

    private fun secondsUntilEndOfDay(): Long {
        val now = Instant.now(clock)
        val endOfDay = now.atZone(UTC)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(UTC)
            .toInstant()
        return endOfDay.epochSecond - now.epochSecond
    }

    // ===== 降级日志节流（进程内状态；短窗口/日窗口共用） =====

    /** prefix -> 最近一次 ERROR 日志时间（epoch ms）。compute 原子更新。 */
    private val degradedLastLogAt = ConcurrentHashMap<String, Long>()

    /** 当前处于降级状态的 subject 前缀（用于恢复日志）。 */
    private val degradedActive: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * subject 前缀：key 去掉最后一段主体标识（IP/iid 等）。
     * 用于降级日志节流分桶与脱敏——日志绝不带 IP/iid 原文。
     */
    private fun subjectPrefix(key: String): String = key.substringBeforeLast(':')

    private fun onDegraded(prefix: String, windowSec: Long, exceptionType: String) {
        degradedActive.add(prefix)
        val now = System.currentTimeMillis()
        // compute 原子：同一前缀首次必打，之后每分钟最多 1 条（多线程并发只放行一条）
        var shouldLog = false
        degradedLastLogAt.compute(prefix) { _, last ->
            if (last == null || now - last >= DEGRADED_LOG_INTERVAL_MS) {
                shouldLog = true
                now
            } else {
                last
            }
        }
        if (shouldLog) {
            log.error(
                "event=ratelimit.degraded prefix={} windowSec={} exceptionType={}",
                prefix, windowSec, exceptionType,
            )
        }
    }

    private fun onRecovered(prefix: String) {
        // remove 返回 true = 降级后第一次成功 → 打一条恢复日志
        if (degradedActive.remove(prefix)) {
            log.info("event=ratelimit.recovered prefix={}", prefix)
        }
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
        private const val MINUTE_WINDOW_SEC = 60L
        private const val DEGRADED_LOG_INTERVAL_MS = 60_000L
    }
}

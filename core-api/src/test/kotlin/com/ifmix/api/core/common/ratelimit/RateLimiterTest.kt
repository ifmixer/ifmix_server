package com.ifmix.core.api.infra.ratelimit

import com.ifmix.core.api.infra.http.ActionContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

@ExtendWith(MockitoExtension::class)
class RateLimiterTest {

    @Mock
    private lateinit var redis: StringRedisTemplate

    @Mock
    private lateinit var ops: org.springframework.data.redis.core.ValueOperations<String, String>

    private lateinit var limiter: RateLimiter
    private val ctx = ActionContext(projectId = "test-app")

    @BeforeEach
    fun setUp() {
        org.mockito.Mockito.`lenient`().whenever(redis.opsForValue()).thenReturn(ops)
        val config = RateLimitConfig(free = 5)
        limiter = RateLimiter(redis, FreeTierResolver(), config)
    }

    // ===== tier 版（历史遗留，行为保持不变） =====

    @Test
    fun `first request is allowed`() {
        whenever(ops.increment(org.mockito.ArgumentMatchers.anyString(), eq(1L))).thenReturn(1L)
        org.mockito.Mockito.`lenient`().whenever(redis.expire(
            org.mockito.ArgumentMatchers.anyString(),
            eq(3600L),
            eq(TimeUnit.SECONDS),
        )).thenReturn(true)

        val result = limiter.check(ctx, "user-1")
        assertThat(result.allowed).isTrue()
        assertThat(result.count).isEqualTo(1L)
        assertThat(result.limit).isEqualTo(5L)
    }

    @Test
    fun `requests within limit are allowed`() {
        whenever(ops.increment(org.mockito.ArgumentMatchers.anyString(), eq(1L))).thenReturn(3L)
        org.mockito.Mockito.`lenient`().whenever(redis.expire(
            org.mockito.ArgumentMatchers.anyString(),
            eq(3600L),
            eq(TimeUnit.SECONDS),
        )).thenReturn(true)

        val result = limiter.check(ctx, "user-2")
        assertThat(result.allowed).isTrue()
        assertThat(result.count).isEqualTo(3L)
        assertThat(result.limit).isEqualTo(5L)
    }

    @Test
    fun `request exceeding limit is denied`() {
        whenever(ops.increment(org.mockito.ArgumentMatchers.anyString(), eq(1L))).thenReturn(6L)
        org.mockito.Mockito.`lenient`().whenever(redis.delete(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        org.mockito.Mockito.`lenient`().whenever(redis.expire(
            org.mockito.ArgumentMatchers.anyString(),
            eq(3600L),
            eq(TimeUnit.SECONDS),
        )).thenReturn(true)

        val result = limiter.check(ctx, "user-3")
        assertThat(result.allowed).isFalse()
        assertThat(result.count).isEqualTo(6L)
    }

    @Test
    fun `refund decrements counter`() {
        whenever(ops.increment(org.mockito.ArgumentMatchers.anyString(), eq(1L))).thenReturn(3L)
        org.mockito.Mockito.`lenient`().whenever(redis.expire(
            org.mockito.ArgumentMatchers.anyString(),
            eq(3600L),
            eq(TimeUnit.SECONDS),
        )).thenReturn(true)

        limiter.check(ctx, "user-4")
        org.mockito.Mockito.`lenient`().whenever(ops.decrement(org.mockito.ArgumentMatchers.anyString())).thenReturn(2L)
        limiter.refund("user-4")

        verify(ops).decrement(org.mockito.ArgumentMatchers.anyString())
    }

    // ===== 通用版 check(window, key, limit) =====

    @Test
    fun `check allows under limit with key used as-is for minute window`() {
        val target = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5))
        val key = "ratelimit:app:install:ip:min:1.2.3.4"
        whenever(ops.increment(any(), eq(1L))).thenReturn(1L)
        whenever(redis.expire(any(), eq(60L), eq(SECONDS))).thenReturn(true)

        val result = target.check(Window.MINUTE, key, 100)

        assertThat(result).isEqualTo(RateLimitResult.Allowed)
        verify(ops).increment(eq(key), eq(1L))
        // 首次 INCR 置窗口 TTL = 60s
        verify(redis).expire(eq(key), eq(60L), eq(SECONDS))
    }

    @Test
    fun `check limited returns redis ttl as retryAfterSec`() {
        val target = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5))
        whenever(ops.increment(any(), eq(1L))).thenReturn(11L)
        whenever(redis.getExpire(any(), eq(SECONDS))).thenReturn(42L)

        val result = target.check(Window.MINUTE, "ratelimit:app:install:ip:min:1.2.3.4", 10)

        assertThat(result).isEqualTo(RateLimitResult.Limited(42))
    }

    @Test
    fun `check degraded and allows when incr returns null`() {
        val target = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5))
        whenever(ops.increment(any(), eq(1L))).thenReturn(null)

        val result = target.check(Window.MINUTE, "ratelimit:app:install:ip:min:1.2.3.4", 100)

        assertThat(result).isEqualTo(RateLimitResult.Degraded)
    }

    @Test
    fun `check degraded and allows when incr throws`() {
        val target = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5))
        whenever(ops.increment(any(), eq(1L))).thenThrow(RuntimeException("redis down"))

        val result = target.check(Window.MINUTE, "ratelimit:app:install:ip:min:1.2.3.4", 100)

        assertThat(result).isEqualTo(RateLimitResult.Degraded)
    }

    @Test
    fun `check utc-day appends utc date to key and ttl until midnight`() {
        // 10:00 UTC → 距当天 UTC 零点（次日 00:00）14h = 50400s
        val clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC)
        val target = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5), clock)
        val key = "ratelimit:app:scan:legacy:ip:day:1.2.3.4"
        whenever(ops.increment(any(), eq(1L))).thenReturn(1L)
        whenever(redis.expire(any(), eq(50_400L), eq(SECONDS))).thenReturn(true)

        val result = target.check(Window.UTC_DAY, key, 500)

        assertThat(result).isEqualTo(RateLimitResult.Allowed)
        verify(ops).increment(eq("$key:2026-10-04"), eq(1L))
        verify(redis).expire(eq("$key:2026-10-04"), eq(50_400L), eq(SECONDS))
    }

    @Test
    fun `check utc-day key rolls over across utc days`() {
        val key = "ratelimit:app:scan:legacy:ip:day:1.2.3.4"
        whenever(ops.increment(any(), eq(1L))).thenReturn(1L)
        whenever(redis.expire(any(), any(), eq(SECONDS))).thenReturn(true)

        // day1 23:59:59 → TTL 1s，key 后缀 2026-10-04
        val day1 = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5), Clock.fixed(Instant.parse("2026-10-04T23:59:59Z"), ZoneOffset.UTC))
        assertThat(day1.check(Window.UTC_DAY, key, 500)).isEqualTo(RateLimitResult.Allowed)
        verify(ops).increment(eq("$key:2026-10-04"), eq(1L))
        verify(redis).expire(eq("$key:2026-10-04"), eq(1L), eq(SECONDS))

        // day2 同一 key 前缀 → 日期后缀翻转为 2026-10-05（新计数器）
        val day2 = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5), Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC))
        assertThat(day2.check(Window.UTC_DAY, key, 500)).isEqualTo(RateLimitResult.Allowed)
        verify(ops).increment(eq("$key:2026-10-05"), eq(1L))
        verify(redis).expire(eq("$key:2026-10-05"), eq(86_400L), eq(SECONDS))
    }

    @Test
    fun `degraded allows request and throttles error log per prefix`() {
        val appender = attachLogAppender()
        try {
            val target = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5))
            whenever(ops.increment(any(), eq(1L))).thenThrow(RuntimeException("redis down"))

            // 同一前缀（同 action、不同 IP）：首次必打，之后 1 分钟内不再打
            repeat(5) {
                val res = target.check(Window.MINUTE, "ratelimit:app:install:ip:min:1.2.3.4", 100)
                assertThat(res).isEqualTo(RateLimitResult.Degraded)
            }
            // 不同前缀（不同 action）：各打各的第一条
            target.check(Window.MINUTE, "ratelimit:app:scan:legacy:ip:min:1.2.3.4", 5)

            val errors = appender.list.filter { it.formattedMessage.contains("event=ratelimit.degraded") }
            assertThat(errors).hasSize(2)
            assertThat(errors[0].formattedMessage)
                .contains("prefix=ratelimit:app:install:ip:min")
                .contains("exceptionType=RuntimeException")
            // 日志不带 IP 原文
            assertThat(errors.joinToString("|")).doesNotContain("1.2.3.4")
        } finally {
            detachLogAppender(appender)
        }
    }

    @Test
    fun `recovered info logged once on first success after degraded`() {
        val appender = attachLogAppender()
        try {
            val target = RateLimiter(redis, FreeTierResolver(), RateLimitConfig(free = 5))
            whenever(ops.increment(any(), eq(1L))).thenThrow(RuntimeException("redis down"))
            assertThat(target.check(Window.MINUTE, "ratelimit:app:install:ip:min:1.2.3.4", 100))
                .isEqualTo(RateLimitResult.Degraded)

            // 恢复：INCR 成功 → 第一条成功打 INFO
            whenever(ops.increment(any(), eq(1L))).thenReturn(1L)
            whenever(redis.expire(any(), eq(60L), eq(SECONDS))).thenReturn(true)
            assertThat(target.check(Window.MINUTE, "ratelimit:app:install:ip:min:1.2.3.4", 100))
                .isEqualTo(RateLimitResult.Allowed)
            // 后续成功不再打
            assertThat(target.check(Window.MINUTE, "ratelimit:app:install:ip:min:1.2.3.4", 100))
                .isEqualTo(RateLimitResult.Allowed)

            val infos = appender.list.filter { it.formattedMessage.contains("event=ratelimit.recovered") }
            assertThat(infos).hasSize(1)
            assertThat(infos[0].formattedMessage).contains("prefix=ratelimit:app:install:ip:min")
        } finally {
            detachLogAppender(appender)
        }
    }

    // ===== helpers =====

    private fun rateLimiterLogger() =
        LoggerFactory.getLogger(RateLimiter::class.java) as ch.qos.logback.classic.Logger

    private fun attachLogAppender(): ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> {
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        rateLimiterLogger().addAppender(appender)
        return appender
    }

    private fun detachLogAppender(appender: ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>) {
        rateLimiterLogger().detachAppender(appender)
    }
}

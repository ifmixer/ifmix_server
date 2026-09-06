package com.ifmix.core.api.infra.ratelimit

import com.ifmix.core.api.infra.http.OperationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit

@ExtendWith(MockitoExtension::class)
class RateLimiterTest {

    @Mock
    private lateinit var redis: StringRedisTemplate

    @Mock
    private lateinit var ops: org.springframework.data.redis.core.ValueOperations<String, String>

    private lateinit var limiter: RateLimiter
    private val ctx = OperationContext(appId = UUID.fromString("00000000-0000-0000-0000-000000000099"))

    @BeforeEach
    fun setUp() {
        org.mockito.Mockito.`lenient`().whenever(redis.opsForValue()).thenReturn(ops)
        val config = RateLimitConfig(free = 5)
        limiter = RateLimiter(redis, FreeTierResolver(), config)
    }

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
}

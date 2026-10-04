package com.ifmix.core.api.infra.attest

import com.ifmix.core.api.infra.attest.AttestReplayGuard.MarkResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * AttestReplayGuard 单测（规格 §7）：FIRST → REPLAY；Redis 异常 → DEGRADED（mock StringRedisTemplate）。
 */
@ExtendWith(MockitoExtension::class)
class AttestReplayGuardTest {

    @Mock
    private lateinit var redis: StringRedisTemplate

    @Mock
    private lateinit var ops: ValueOperations<String, String>

    private lateinit var guard: AttestReplayGuard

    @BeforeEach
    fun setUp() {
        lenient().whenever(redis.opsForValue()).thenReturn(ops)
        guard = AttestReplayGuard(redis)
    }

    @Test
    fun `first mark returns FIRST - second returns REPLAY`() {
        whenever(ops.setIfAbsent(anyString(), eq("1"), any<Duration>()))
            .thenReturn(true)
            .thenReturn(false)
        assertThat(guard.markUsed("attest:used:abc", 360)).isEqualTo(MarkResult.FIRST)
        assertThat(guard.markUsed("attest:used:abc", 360)).isEqualTo(MarkResult.REPLAY)
    }

    @Test
    fun `different keys are independent`() {
        whenever(ops.setIfAbsent(anyString(), eq("1"), any<Duration>())).thenReturn(true)
        assertThat(guard.markUsed("attest:used:one", 360)).isEqualTo(MarkResult.FIRST)
        assertThat(guard.markUsed("attest:used:two", 360)).isEqualTo(MarkResult.FIRST)
    }

    @Test
    fun `null response from redis degrades`() {
        whenever(ops.setIfAbsent(anyString(), eq("1"), any<Duration>())).thenReturn(null)
        assertThat(guard.markUsed("attest:used:abc", 360)).isEqualTo(MarkResult.DEGRADED)
    }

    @Test
    fun `redis exception degrades and does not propagate`() {
        whenever(ops.setIfAbsent(anyString(), eq("1"), any<Duration>()))
            .thenThrow(RuntimeException("connection refused"))
        assertThat(guard.markUsed("attest:used:abc", 360)).isEqualTo(MarkResult.DEGRADED)
        // 节流窗口内的再次降级也不抛异常（节流控制器吞掉重复告警）
        assertThat(guard.markUsed("attest:used:def", 360)).isEqualTo(MarkResult.DEGRADED)
    }

    @Test
    fun `recovery after degradation returns FIRST again`() {
        whenever(ops.setIfAbsent(anyString(), eq("1"), any<Duration>()))
            .thenReturn(null)   // 降级
            .thenReturn(true)   // 恢复
        assertThat(guard.markUsed("attest:used:abc", 360)).isEqualTo(MarkResult.DEGRADED)
        assertThat(guard.markUsed("attest:used:abc", 360)).isEqualTo(MarkResult.FIRST)
    }

    @Test
    fun `ttl is passed through to redis`() {
        val seen = mutableListOf<Duration>()
        whenever(ops.setIfAbsent(anyString(), eq("1"), any<Duration>())).thenAnswer { invocation ->
            seen.add(invocation.getArgument(2))
            true
        }
        guard.markUsed("attest:used:abc", 360)
        assertThat(seen).containsExactly(Duration.ofSeconds(360))
    }
}

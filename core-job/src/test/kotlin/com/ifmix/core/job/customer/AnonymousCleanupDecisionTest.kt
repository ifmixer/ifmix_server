package com.ifmix.core.job.customer

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.job.customer.AnonymousCleanupDecision.CustomerState
import org.junit.jupiter.api.Test

class AnonymousCleanupDecisionTest {

    private fun state(
        anonymous: Boolean = false,
        merged: Boolean = false,
        tombstoneExpired: Boolean = false,
        hasValidToken: Boolean = false,
        hasActiveSubscription: Boolean = false,
    ) = CustomerState(anonymous, merged, tombstoneExpired, hasValidToken, hasActiveSubscription)

    @Test
    fun `未合并匿名僵尸_无 token 应删`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(anonymous = true))).isTrue()
    }

    @Test
    fun `匿名但持有有效 token 不删`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(anonymous = true, hasValidToken = true))).isFalse()
    }

    @Test
    fun `正常已登录用户（非匿名_未合并）恒不删`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state())).isFalse()
    }

    @Test
    fun `已合并且超审计窗口应删`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(merged = true, tombstoneExpired = true))).isTrue()
    }

    @Test
    fun `已合并但未超审计窗口不删`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(merged = true, tombstoneExpired = false))).isFalse()
    }

    @Test
    fun `active 订阅一律跳过_即便僵尸`() {
        assertThat(
            AnonymousCleanupDecision.shouldDelete(state(anonymous = true, hasActiveSubscription = true)),
        ).isFalse()
    }

    @Test
    fun `active 订阅一律跳过_即便 tombstone`() {
        assertThat(
            AnonymousCleanupDecision.shouldDelete(
                state(merged = true, tombstoneExpired = true, hasActiveSubscription = true),
            ),
        ).isFalse()
    }
}

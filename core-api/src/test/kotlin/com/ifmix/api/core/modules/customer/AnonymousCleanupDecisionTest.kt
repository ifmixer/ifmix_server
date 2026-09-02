package com.ifmix.core.api.modules.customer

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.api.modules.customer.handler.AnonymousCleanupDecision
import com.ifmix.core.api.modules.customer.handler.AnonymousCleanupDecision.CustomerState
import org.junit.jupiter.api.Test

/**
 * 阶段 6 匿名清理「是否应删」纯判定测试（无 DB/Spring）。
 * 覆盖 anonymous / mergedTo / 有无有效 token / 有无 active 订阅 的组合。
 */
class AnonymousCleanupDecisionTest {

    private fun state(
        anonymous: Boolean = false,
        merged: Boolean = false,
        tombstoneExpired: Boolean = false,
        hasValidToken: Boolean = false,
        hasActiveSubscription: Boolean = false,
    ) = CustomerState(anonymous, merged, tombstoneExpired, hasValidToken, hasActiveSubscription)

    // ===== 应删：未合并匿名僵尸 =====
    @Test
    fun `anonymous zombie without valid token - delete`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(anonymous = true, hasValidToken = false))).isTrue()
    }

    @Test
    fun `anonymous with valid token - keep`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(anonymous = true, hasValidToken = true))).isFalse()
    }

    // ===== 应删：已合并 tombstone 超窗 =====
    @Test
    fun `merged tombstone expired - delete`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(merged = true, tombstoneExpired = true))).isTrue()
        assertThat(AnonymousCleanupDecision.shouldDelete(state(anonymous = true, merged = true, tombstoneExpired = true))).isTrue()
    }

    @Test
    fun `merged tombstone within window - keep`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(merged = true, tombstoneExpired = false))).isFalse()
    }

    // ===== 绝不删：正常已登录用户 =====
    @Test
    fun `normal logged-in user - never delete`() {
        assertThat(AnonymousCleanupDecision.shouldDelete(state(anonymous = false, merged = false, hasValidToken = false))).isFalse()
        assertThat(AnonymousCleanupDecision.shouldDelete(state(anonymous = false, merged = false, hasValidToken = true))).isFalse()
    }

    // ===== active 订阅一律跳过（匿名却付费边界）=====
    @Test
    fun `active subscription overrides delete - keep`() {
        assertThat(
            AnonymousCleanupDecision.shouldDelete(state(anonymous = true, hasValidToken = false, hasActiveSubscription = true))
        ).isFalse()
        assertThat(
            AnonymousCleanupDecision.shouldDelete(state(merged = true, tombstoneExpired = true, hasActiveSubscription = true))
        ).isFalse()
    }
}

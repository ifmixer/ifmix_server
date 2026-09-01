package com.ifmix.api.core.modules.customer

import com.ifmix.api.core.modules.customer.handler.AnonymousCleanupDecision
import com.ifmix.api.core.modules.customer.handler.AnonymousCleanupDecision.CustomerState
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertTrue(AnonymousCleanupDecision.shouldDelete(state(anonymous = true, hasValidToken = false)))
    }

    @Test
    fun `anonymous with valid token - keep`() {
        // 有有效 token 说明客户端仍可 attach，不删
        assertFalse(AnonymousCleanupDecision.shouldDelete(state(anonymous = true, hasValidToken = true)))
    }

    // ===== 应删：已合并 tombstone 超窗 =====
    @Test
    fun `merged tombstone expired - delete`() {
        // 已合并空壳（无论 anonymous 值），超审计窗口即删
        assertTrue(AnonymousCleanupDecision.shouldDelete(state(merged = true, tombstoneExpired = true)))
        assertTrue(AnonymousCleanupDecision.shouldDelete(state(anonymous = true, merged = true, tombstoneExpired = true)))
    }

    @Test
    fun `merged tombstone within window - keep`() {
        assertFalse(AnonymousCleanupDecision.shouldDelete(state(merged = true, tombstoneExpired = false)))
    }

    // ===== 绝不删：正常已登录用户 =====
    @Test
    fun `normal logged-in user - never delete`() {
        // anonymous=false 且未合并 → 正常用户，任何 token/订阅组合都不删
        assertFalse(AnonymousCleanupDecision.shouldDelete(state(anonymous = false, merged = false, hasValidToken = false)))
        assertFalse(AnonymousCleanupDecision.shouldDelete(state(anonymous = false, merged = false, hasValidToken = true)))
    }

    // ===== active 订阅一律跳过（匿名却付费边界）=====
    @Test
    fun `active subscription overrides delete - keep`() {
        // 本该删的僵尸/tombstone，只要有 active 订阅就跳过
        assertFalse(
            AnonymousCleanupDecision.shouldDelete(
                state(anonymous = true, hasValidToken = false, hasActiveSubscription = true)
            )
        )
        assertFalse(
            AnonymousCleanupDecision.shouldDelete(
                state(merged = true, tombstoneExpired = true, hasActiveSubscription = true)
            )
        )
    }
}

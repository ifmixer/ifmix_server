package com.ifmix.core.api.modules.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.ifmix.core.api.modules.auth.handler.AuthAggHandler.Companion.LoginAction
import com.ifmix.core.api.modules.auth.handler.AuthAggHandler.Companion.decideLoginAction
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 阶段4 登录判定表四分支（R1：合并方向硬编码 匿名 cur → existing）。
 * 纯逻辑，无 DB/Spring。
 */
class LoginActionDecisionTest {

    private val cur = UUID.randomUUID()
    private val existing = UUID.randomUUID()

    @Test
    fun `branch 1 - relation absent - promote or create`() {
        // relation 不存在 → 转正/新建（不论 cur 是否匿名、是否为 null）
        assertThat(decideLoginAction(cur, curAnonymous = true, existing = null)).isEqualTo(LoginAction.PromoteOrCreate)
        assertThat(decideLoginAction(cur, curAnonymous = false, existing = null)).isEqualTo(LoginAction.PromoteOrCreate)
        assertThat(decideLoginAction(cur = null, curAnonymous = true, existing = null)).isEqualTo(LoginAction.PromoteOrCreate)
    }

    @Test
    fun `branch 2 - existing equals cur - noop`() {
        val action = decideLoginAction(cur, curAnonymous = true, existing = cur)
        assertThat(action).isInstanceOf(LoginAction.NoOp::class)
        assertThat((action as LoginAction.NoOp).owner).isEqualTo(cur)
        // 已转正后重复登录也是 NoOp
        assertThat(decideLoginAction(cur, curAnonymous = false, existing = cur)).isInstanceOf(LoginAction.NoOp::class)
    }

    @Test
    fun `branch 3 - anonymous cur and different existing - merge cur into existing`() {
        val action = decideLoginAction(cur, curAnonymous = true, existing = existing)
        assertThat(action).isInstanceOf(LoginAction.Merge::class)
        action as LoginAction.Merge
        // R1：方向必须 from=cur(匿名) → to=existing，绝不反向
        assertThat(action.from).isEqualTo(cur)
        assertThat(action.to).isEqualTo(existing)
    }

    @Test
    fun `branch 4 - non-anonymous cur and different existing - conflict`() {
        assertThat(decideLoginAction(cur, curAnonymous = false, existing = existing)).isEqualTo(LoginAction.Conflict)
    }

    @Test
    fun `merge direction is never reversed`() {
        // 穷举匿名合并分支：from 恒为 cur，to 恒为 existing
        repeat(50) {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val action = decideLoginAction(a, curAnonymous = true, existing = b) as LoginAction.Merge
            assertThat(action.from).isEqualTo(a)
            assertThat(action.to).isEqualTo(b)
        }
    }
}

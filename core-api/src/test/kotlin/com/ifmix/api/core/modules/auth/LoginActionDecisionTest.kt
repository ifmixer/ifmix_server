package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.modules.auth.handler.AuthAggHandler.Companion.LoginAction
import com.ifmix.api.core.modules.auth.handler.AuthAggHandler.Companion.decideLoginAction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(LoginAction.PromoteOrCreate, decideLoginAction(cur, curAnonymous = true, existing = null))
        assertEquals(LoginAction.PromoteOrCreate, decideLoginAction(cur, curAnonymous = false, existing = null))
        assertEquals(LoginAction.PromoteOrCreate, decideLoginAction(cur = null, curAnonymous = true, existing = null))
    }

    @Test
    fun `branch 2 - existing equals cur - noop`() {
        val action = decideLoginAction(cur, curAnonymous = true, existing = cur)
        assertTrue(action is LoginAction.NoOp)
        assertEquals(cur, (action as LoginAction.NoOp).owner)
        // 已转正后重复登录也是 NoOp
        assertTrue(decideLoginAction(cur, curAnonymous = false, existing = cur) is LoginAction.NoOp)
    }

    @Test
    fun `branch 3 - anonymous cur and different existing - merge cur into existing`() {
        val action = decideLoginAction(cur, curAnonymous = true, existing = existing)
        assertTrue(action is LoginAction.Merge)
        action as LoginAction.Merge
        // R1：方向必须 from=cur(匿名) → to=existing，绝不反向
        assertEquals(cur, action.from)
        assertEquals(existing, action.to)
    }

    @Test
    fun `branch 4 - non-anonymous cur and different existing - conflict`() {
        assertEquals(LoginAction.Conflict, decideLoginAction(cur, curAnonymous = false, existing = existing))
    }

    @Test
    fun `merge direction is never reversed`() {
        // 穷举匿名合并分支：from 恒为 cur，to 恒为 existing
        repeat(50) {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val action = decideLoginAction(a, curAnonymous = true, existing = b)
            action as LoginAction.Merge
            assertEquals(a, action.from)
            assertEquals(b, action.to)
        }
    }
}

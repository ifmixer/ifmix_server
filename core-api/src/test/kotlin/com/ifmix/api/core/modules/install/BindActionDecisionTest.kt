package com.ifmix.api.core.modules.install

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.ifmix.core.api.modules.install.handler.InstallAggHandler.Companion.BindAction
import com.ifmix.core.api.modules.install.handler.InstallAggHandler.Companion.decideBindAction
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * install↔customer 绑定判定（纯函数）：按既有行状态决定 插/复活/不动。
 * 纯逻辑，无 DB/Spring。
 */
class BindActionDecisionTest {
    private val id = UUID.randomUUID()

    @Test
    fun `no existing row - insert`() {
        assertThat(decideBindAction(existingId = null, existingDeleted = false))
            .isEqualTo(BindAction.Insert)
    }

    @Test
    fun `existing active row - noop`() {
        assertThat(decideBindAction(existingId = id, existingDeleted = false))
            .isInstanceOf(BindAction.NoOp::class)
    }

    @Test
    fun `existing soft-deleted row - reactivate`() {
        val action = decideBindAction(existingId = id, existingDeleted = true)
        assertThat(action).isInstanceOf(BindAction.Reactivate::class)
        assertThat((action as BindAction.Reactivate).id).isEqualTo(id)
    }
}

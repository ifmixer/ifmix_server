package com.ifmix.core.api.modules.install.handler

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class InstallAggHandler {

    companion object {
        sealed interface BindAction {
            /** 无任何行 → 插新行。 */
            data object Insert : BindAction
            /** 已有有效行 → 幂等不动。 */
            data object NoOp : BindAction
            /** 有软删行 → 复活。 */
            data class Reactivate(val id: UUID) : BindAction
        }

        /**
         * 绑定判定（纯函数）：按 (install, customer) 的既有行状态决定 插/复活/不动。
         * @param existingId 既有行 id（含软删查得），null=无行
         * @param existingDeleted 既有行是否已软删
         */
        fun decideBindAction(existingId: UUID?, existingDeleted: Boolean): BindAction = when {
            existingId == null -> BindAction.Insert
            existingDeleted -> BindAction.Reactivate(existingId)
            else -> BindAction.NoOp
        }
    }
}

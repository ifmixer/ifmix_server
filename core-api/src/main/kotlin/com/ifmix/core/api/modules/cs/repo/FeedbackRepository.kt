package com.ifmix.core.api.modules.cs.repo

import com.ifmix.core.api.entity.cs.Feedback
import com.ifmix.core.api.entity.cs.projectId
import com.ifmix.core.api.entity.cs.customerId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FeedbackRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(Feedback::class) }

    fun save(mc: ModuleCtx, entity: Feedback) = tpl.save(mc, entity)

    /** 阶段 6：物理删除某批 customer 名下反馈（有 customer_id，避免孤儿行）。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, projectId: UUID, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(Feedback::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.projectId eq projectId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }
}

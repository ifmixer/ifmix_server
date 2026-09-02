package com.ifmix.core.api.modules.cms.repo

import com.ifmix.core.api.entity.cms.Feedback
import com.ifmix.core.api.entity.cms.appId
import com.ifmix.core.api.entity.cms.customerId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FeedbackRepository {
    companion object { private val tpl = AppCrudRepoTemplate(Feedback::class) }

    fun save(mc: ModuleCtx, entity: Feedback) = tpl.save(mc, entity)

    /** 阶段 6：物理删除某批 customer 名下反馈（有 customer_id，避免孤儿行）。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, appId: UUID, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(Feedback::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.appId eq appId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }
}

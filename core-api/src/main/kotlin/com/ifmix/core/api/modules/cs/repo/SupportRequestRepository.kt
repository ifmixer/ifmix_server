package com.ifmix.core.api.modules.cs.repo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.cs.SupportRequest
import com.ifmix.core.api.entity.cs.projectId
import com.ifmix.core.api.entity.cs.customerId
import com.ifmix.core.api.entity.cs.id
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
open class SupportRequestRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(SupportRequest::class) }

    open fun save(mc: ModuleCtx, entity: SupportRequest) = tpl.save(mc, entity)

    /** owner-scoped 详情：仅返回 projectId + customerId 名下的记录。 */
    fun findByIdOwned(mc: ModuleCtx, projectId: UUID, customerId: UUID, id: UUID): SupportRequest? =
        mc.sql.createQuery(SupportRequest::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    /** owner-scoped 列表：按 id 倒序（UuidV7 单调 ≈ 创建时间）游标翻页。 */
    fun findMineByCursor(mc: ModuleCtx, projectId: UUID, customerId: UUID, limit: Int, cursor: UUID?): Page<SupportRequest> {
        val items = mc.sql.createQuery(SupportRequest::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit + 1).execute()
        return Page.of(items, limit) { it.id.toString() }
    }

    /** 阶段 6：物理删除某批 customer 名下工单（有 customer_id，避免孤儿行）。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, projectId: UUID, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(SupportRequest::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.projectId eq projectId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }
}

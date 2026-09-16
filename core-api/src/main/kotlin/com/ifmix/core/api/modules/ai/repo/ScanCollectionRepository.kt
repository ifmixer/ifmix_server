package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.entity.ai.ScanCollection
import com.ifmix.core.api.entity.ai.projectId
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.isDefault
import com.ifmix.core.api.entity.ai.customerId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.ne
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(ScanCollection::class, UUID::class) }

    fun findDefault(mc: ModuleCtx, projectId: String, customerId: UUID?): ScanCollection? {
        if (customerId == null) return null
        return mc.sql.createQuery(ScanCollection::class) {
            where(table.get<String>("projectId") eq projectId)
            where(table.isDefault eq true)
            where(table.customerId eq customerId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun save(mc: ModuleCtx, entity: ScanCollection) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, projectId: String, id: UUID) = tpl.findById(mc, projectId, id)

    /** 合并：把 fromCustomerId 名下收藏夹归属改到 toCustomerId。返回改写行数。 */
    fun reassignOwner(mc: ModuleCtx, projectId: String, fromCustomerId: UUID, toCustomerId: UUID): Int =
        mc.sql.createUpdate(ScanCollection::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq fromCustomerId)
            set(table.customerId, toCustomerId)
        }.execute()

    /**
     * is_default 去重：合并后 toCustomerId 名下可能有多个 isDefault=true。
     * 保留 keepId（existing 原有的默认），将该 customer 名下其余默认收藏夹降级为 false。
     * 返回降级行数。
     */
    fun demoteOtherDefaults(mc: ModuleCtx, projectId: String, customerId: UUID, keepId: UUID): Int =
        mc.sql.createUpdate(ScanCollection::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.isDefault eq true)
            where(table.id ne keepId)
            set(table.isDefault, false)
        }.execute()
    fun deleteById(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.deleteById(mc, projectId, id)
    fun exists(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.exists(mc, projectId, id)

    // ===== 阶段 6：匿名清理 =====

    /** 查某批 customer 名下所有收藏夹 id（用于级联删 scan_collection_item，避免孤儿行）。 */
    fun findIdsByCustomers(mc: ModuleCtx, projectId: String, customerIds: Collection<UUID>): List<UUID> {
        if (customerIds.isEmpty()) return emptyList()
        return mc.sql.createQuery(ScanCollection::class) {
            where(table.projectId eq projectId)
            where(table.customerId valueIn customerIds)
            select(table.id)
        }.execute()
    }

    /** 物理删除某批 customer 名下收藏夹。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, projectId: String, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(ScanCollection::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.projectId eq projectId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }
}

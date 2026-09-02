package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.ai.ScanCollectionItem
import com.ifmix.core.api.entity.ai.appId
import com.ifmix.core.api.entity.ai.collectionId
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.scanRecordId
import com.ifmix.core.api.entity.ai.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanCollectionItemRepository {
    companion object { private val tpl = AppCrudRepoTemplate(ScanCollectionItem::class) }

    fun insertIfAbsent(mc: ModuleCtx, appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID {
        val existing = mc.sql.createQuery(ScanCollectionItem::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.get<UUID>("collectionId") eq collectionId)
            where(table.get<UUID>("scanRecordId") eq scanRecordId)
            select(table)
        }.limit(1).execute().firstOrNull()

        if (existing != null) return existing.id

        val id = UuidV7.generate()
        val entity = ScanCollectionItem {
            this.id = id
            this.appId = appId
            this.collectionId = collectionId
            this.scanRecordId = scanRecordId
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        mc.sql.entities.save(entity)
        return id
    }

    fun softDeleteByScanIds(mc: ModuleCtx, appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Int {
        if (scanRecordIds.isEmpty()) return 0
        return mc.sql.createUpdate(ScanCollectionItem::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.get<UUID>("collectionId") eq collectionId)
            where(table.get<UUID>("scanRecordId") valueIn scanRecordIds)
        }.execute()
    }

    fun findItemsByCursor(mc: ModuleCtx, appId: UUID, collectionId: UUID, limit: Int, cursor: UUID?): Page<ScanCollectionItem> {
        val items = mc.sql.createQuery(ScanCollectionItem::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.get<UUID>("collectionId") eq collectionId)
            cursor?.let { where(table.getId<UUID>() lt it) }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(items, limit) { it.id.toString() }
    }

    fun existsByScanRecordId(mc: ModuleCtx, collectionId: UUID, scanRecordId: UUID): Boolean {
        return mc.sql.createQuery(ScanCollectionItem::class) {
            where(table.get<UUID>("collectionId") eq collectionId)
            where(table.get<UUID>("scanRecordId") eq scanRecordId)
            select(table)
        }.limit(1).execute().isNotEmpty()
    }

    fun save(mc: ModuleCtx, entity: ScanCollectionItem) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)

    /** 阶段 6：级联物理删除某批收藏夹下的所有 item（无软删列，避免孤儿行）。 */
    fun physicalDeleteByCollections(mc: ModuleCtx, appId: UUID, collectionIds: Collection<UUID>): Int {
        if (collectionIds.isEmpty()) return 0
        return mc.sql.createDelete(ScanCollectionItem::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.get<UUID>("appId") eq appId)
            where(table.collectionId valueIn collectionIds)
        }.execute()
    }
}

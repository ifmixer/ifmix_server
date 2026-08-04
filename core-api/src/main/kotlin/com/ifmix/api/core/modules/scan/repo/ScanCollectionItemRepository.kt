package com.ifmix.api.core.modules.scan.repo

import com.ifmix.api.core.entity.collection.ScanCollectionItem
import com.ifmix.api.core.entity.collection.dto.ScanCollectionItemView
import com.ifmix.api.core.entity.collection.appId
import com.ifmix.api.core.entity.collection.collectionId
import com.ifmix.api.core.entity.collection.scanRecordId
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanCollectionItemRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanCollectionItem>(sql, ScanCollectionItem::class) {

    fun insertIfAbsent(ctx: RepoContext, appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID {
        val existing = sql.createQuery(ScanCollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.fetchOneOrNull()

        if (existing != null) return existing.id

        val now = Instant.now()
        val item = ScanCollectionItem {
            id = UuidV7.generate()
            this.appId = appId
            collection { id = collectionId }
            scanRecord { id = scanRecordId }
            createdAt = now
            updatedAt = now
            deletedAt = null
        }
        return save(ctx, item).id
    }

    fun softDeleteByScanIds(ctx: RepoContext, appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Long {
        if (scanRecordIds.isEmpty()) return 0L
        val items = sql.createQuery(ScanCollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            where(table.scanRecordId valueIn scanRecordIds)
            select(table)
        }.execute()
        items.forEach { deleteById(ctx, it.id) }
        return items.size.toLong()
    }

    fun findItemsByCursor(
        ctx: RepoContext,
        appId: UUID,
        collectionId: UUID,
        limit: Int,
        cursor: UUID?,
    ): Page<ScanCollectionItemView> {
        val items = sql.createQuery(ScanCollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            if (cursor != null) {
                where(table.getId<UUID>() lt cursor)
            }
            orderBy(table.getId<UUID>().desc())
            select(table.fetch(ScanCollectionItemView::class))
        }.limit(limit + 1).execute()

        return Page.of(items, limit) { it.id.toString() }
    }

    fun existsByScanRecordId(ctx: RepoContext, collectionId: UUID, scanRecordId: UUID): Boolean {
        return sql.createQuery(ScanCollectionItem::class) {
            where(table.collectionId eq collectionId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.limit(1).execute().isNotEmpty()
    }
}

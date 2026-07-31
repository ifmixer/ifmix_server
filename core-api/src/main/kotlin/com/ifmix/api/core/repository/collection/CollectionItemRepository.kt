package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.CollectionItem
import com.ifmix.api.core.entity.collection.appId
import com.ifmix.api.core.entity.collection.collectionId
import com.ifmix.api.core.entity.collection.createdAt
import com.ifmix.api.core.entity.collection.id
import com.ifmix.api.core.entity.collection.scanRecordId
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Component
import com.ifmix.api.core.infra.db.UuidV7
import java.time.Instant
import java.util.UUID

/** CollectionItem repository with custom business operations */
@Component
class CollectionItemRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<CollectionItem>(sql, CollectionItem::class) {

    /**
     * Idempotent insert - returns existing or new item ID.
     * @LogicalDeleted auto-filters deleted items.
     */
    fun insertIfAbsent(appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID {
        val existing = sql.createQuery(CollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.fetchOneOrNull()

        if (existing != null) return existing.id

        // Insert new item using Jimmer draft syntax
        val now = Instant.now()
        val item = CollectionItem {
            id = UuidV7.generate()
            this.appId = appId
            collection { id = collectionId }
            scanRecord { id = scanRecordId }
            createdAt = now
            updatedAt = now
            deletedAt = null
        }
        return save(item).id
    }

    /**
     * Batch soft delete for multiple scan records in a collection.
     * @LogicalDeleted auto-filters already-deleted items.
     */
    fun softDeleteByScanIds(appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Long {
        if (scanRecordIds.isEmpty()) return 0L
        val items = sql.createQuery(CollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            where(table.scanRecordId valueIn scanRecordIds)
            select(table)
        }.execute()
        items.forEach { deleteById(it.id) }
        return items.size.toLong()
    }

    /**
     * Paginated listing with cursor support, joined with scanRecord.
     * @LogicalDeleted auto-filters deleted items.
     */
    fun listWithScanRecords(
        appId: UUID,
        collectionId: UUID,
        limit: Int,
        cursor: UUID?,
    ): Page<CollectionItem> {
        val items = sql.createQuery(CollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            if (cursor != null) {
                where(table.id lt cursor)
            }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit + 1).execute()

        val hasMore = items.size > limit
        val pageItems = if (hasMore) items.take(limit) else items
        val nextCursor = if (hasMore && pageItems.isNotEmpty()) {
            pageItems.last().id.toString()
        } else null

        return Page(pageItems, nextCursor, hasMore)
    }

    /** Check if a scan record exists in a collection (non-deleted, auto-filtered by @LogicalDeleted) */
    fun existsByScanRecordId(collectionId: UUID, scanRecordId: UUID): Boolean {
        val results = sql.createQuery(CollectionItem::class) {
            where(table.collectionId eq collectionId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.limit(1).execute()
        return results.isNotEmpty()
    }
}

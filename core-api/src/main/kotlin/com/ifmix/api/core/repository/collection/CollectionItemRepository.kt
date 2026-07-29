package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.CollectionItem
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.infra.db.Page
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** CollectionItem repository with custom business operations */
@Component
class CollectionItemRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<CollectionItem>(sql, CollectionItem::class) {

    /**
     * Idempotent insert - returns existing or new item ID.
     */
    fun insertIfAbsent(appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID {
        val all = findAll()
        val existing = all.firstOrNull { ci ->
            ci.appId == appId &&
            ci.collection.id == collectionId &&
            ci.scanRecord.id == scanRecordId &&
            ci.deletedAt == null
        }
        existing?.let { return it.id }

        // Insert new item using Jimmer draft syntax
        val now = Instant.now()
        val item = CollectionItem {
            id = UUID.randomUUID()
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
     */
    fun softDeleteByScanIds(appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Long {
        if (scanRecordIds.isEmpty()) return 0L
        val items = findAll().filter { item ->
            item.appId == appId &&
            item.collection.id == collectionId &&
            item.scanRecord.id in scanRecordIds &&
            item.deletedAt == null
        }
        items.forEach { deleteById(it.id) }
        return items.size.toLong()
    }

    /**
     * Paginated listing with cursor support, joined with scanRecord.
     */
    fun listWithScanRecords(
        appId: UUID,
        collectionId: UUID,
        limit: Int,
        cursor: UUID?,
    ): Page<CollectionItem> {
        val all = findAll()
        val filtered = all.filter { it.appId == appId && it.collection.id == collectionId && it.deletedAt == null }
        val sorted = filtered.sortedByDescending { it.createdAt }.sortedByDescending { it.id }

        val itemsAfterCursor = if (cursor != null) {
            sorted.find { it.id == cursor }?.let { sorted.dropWhile { it.id != cursor }.drop(1) } ?: sorted
        } else { sorted }

        val hasMore = itemsAfterCursor.size > limit
        val pageItems = if (hasMore) itemsAfterCursor.take(limit) else itemsAfterCursor

        val nextCursor = if (hasMore && pageItems.isNotEmpty()) {
            pageItems.last().id.toString()
        } else null

        return Page(pageItems, nextCursor, hasMore)
    }

    /** Check if a scan record exists in a collection (non-deleted) */
    fun existsByScanRecordId(collectionId: UUID, scanRecordId: UUID): Boolean {
        val all = findAll()
        return all.any { ci ->
            ci.collection.id == collectionId &&
            ci.scanRecord.id == scanRecordId &&
            ci.deletedAt == null
        }
    }
}

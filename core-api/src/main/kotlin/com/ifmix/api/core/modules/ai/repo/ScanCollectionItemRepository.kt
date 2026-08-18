package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreScanCollectionItem.Companion.CORE_SCAN_COLLECTION_ITEM
import com.ifmix.api.core.entity.scan.ScanCollectionItem
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanCollectionItemRepository(private val crud: CrudRepoOps) {

    fun insertIfAbsent(ctx: SvcCtx, appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID {
        val existing = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION_ITEM)
            .where(CORE_SCAN_COLLECTION_ITEM.APP_ID.eq(appId))
            .and(CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID.eq(collectionId))
            .and(CORE_SCAN_COLLECTION_ITEM.SCAN_RECORD_ID.eq(scanRecordId))
            .and(CORE_SCAN_COLLECTION_ITEM.DELETED_AT.isNull)
            .limit(1)
            .fetchOne()

        if (existing != null) {
            return toModel(existing as org.jooq.Record).id
        }

        val now = Instant.now()
        val id = com.ifmix.api.core.infra.db.UuidV7.generate()
        ctx.dsl.insertInto(
            CORE_SCAN_COLLECTION_ITEM,
            CORE_SCAN_COLLECTION_ITEM.ID,
            CORE_SCAN_COLLECTION_ITEM.APP_ID,
            CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID,
            CORE_SCAN_COLLECTION_ITEM.SCAN_RECORD_ID,
            CORE_SCAN_COLLECTION_ITEM.CREATED_AT,
            CORE_SCAN_COLLECTION_ITEM.UPDATED_AT,
            CORE_SCAN_COLLECTION_ITEM.DELETED_AT,
        ).values(
            id,
            appId,
            collectionId,
            scanRecordId,
            now,
            now,
            null,
        ).execute()
        return id
    }

    fun softDeleteByScanIds(ctx: SvcCtx, appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Long {
        if (scanRecordIds.isEmpty()) return 0L
        val count = ctx.dsl.update(CORE_SCAN_COLLECTION_ITEM)
            .set(CORE_SCAN_COLLECTION_ITEM.DELETED_AT, Instant.now())
            .where(CORE_SCAN_COLLECTION_ITEM.APP_ID.eq(appId))
            .and(CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID.eq(collectionId))
            .and(CORE_SCAN_COLLECTION_ITEM.SCAN_RECORD_ID.`in`(scanRecordIds))
            .execute()
        return count.toLong()
    }

    fun findItemsByCursor(
        ctx: SvcCtx,
        appId: UUID,
        collectionId: UUID,
        limit: Int,
        cursor: UUID?,
    ): Page<ScanCollectionItem> {
        var cond = CORE_SCAN_COLLECTION_ITEM.APP_ID.eq(appId)
            .and(CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID.eq(collectionId))
            .and(CORE_SCAN_COLLECTION_ITEM.DELETED_AT.isNull)
        cursor?.let { cond = cond.and(CORE_SCAN_COLLECTION_ITEM.ID.lt(it)) }

        val items = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION_ITEM)
            .where(cond)
            .orderBy(CORE_SCAN_COLLECTION_ITEM.ID.desc())
            .limit(limit + 1)
            .fetch()
            .map { toModel(it) }

        return Page.of(items, limit) { it.id.toString() }
    }

    fun existsByScanRecordId(ctx: SvcCtx, collectionId: UUID, scanRecordId: UUID): Boolean {
        return ctx.dsl.fetchExists(
            CORE_SCAN_COLLECTION_ITEM,
            CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID.eq(collectionId)
                .and(CORE_SCAN_COLLECTION_ITEM.SCAN_RECORD_ID.eq(scanRecordId))
                .and(CORE_SCAN_COLLECTION_ITEM.DELETED_AT.isNull),
        )
    }

    companion object {
        fun toModel(r: org.jooq.Record): ScanCollectionItem = ScanCollectionItem(
            id = r.get(CORE_SCAN_COLLECTION_ITEM.ID)!!,
            appId = r.get(CORE_SCAN_COLLECTION_ITEM.APP_ID)!!,
            collectionId = r.get(CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID)!!,
            scanRecordId = r.get(CORE_SCAN_COLLECTION_ITEM.SCAN_RECORD_ID)!!,
            createdAt = r.get(CORE_SCAN_COLLECTION_ITEM.CREATED_AT)!!,
            updatedAt = r.get(CORE_SCAN_COLLECTION_ITEM.UPDATED_AT),
            deletedAt = r.get(CORE_SCAN_COLLECTION_ITEM.DELETED_AT),
        )
    }
}

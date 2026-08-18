package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreScanCollectionItem.Companion.CORE_SCAN_COLLECTION_ITEM
import com.ifmix.api.core.entity.ai.ScanCollectionItem
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanCollectionItemRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_SCAN_COLLECTION_ITEM,
        idField = CORE_SCAN_COLLECTION_ITEM.ID,
        appIdField = CORE_SCAN_COLLECTION_ITEM.APP_ID,
        type = ScanCollectionItem::class.java,
        deletedAtField = CORE_SCAN_COLLECTION_ITEM.DELETED_AT,
    )

    fun insertIfAbsent(ctx: SvcCtx, appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID {
        val existing = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION_ITEM)
            .where(CORE_SCAN_COLLECTION_ITEM.APP_ID.eq(appId))
            .and(CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID.eq(collectionId))
            .and(CORE_SCAN_COLLECTION_ITEM.SCAN_RECORD_ID.eq(scanRecordId))
            .and(CORE_SCAN_COLLECTION_ITEM.DELETED_AT.isNull)
            .fetchOneInto(ScanCollectionItem::class.java)

        if (existing != null) return existing.id

        val id = UuidV7.generate()
        crud.insert(ctx, ScanCollectionItem(
            id = id, appId = appId, collectionId = collectionId,
            scanRecordId = scanRecordId, createdAt = Instant.now(),
        ))
        return id
    }

    fun softDeleteByScanIds(ctx: SvcCtx, appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Int {
        if (scanRecordIds.isEmpty()) return 0
        return ctx.dsl.update(CORE_SCAN_COLLECTION_ITEM)
            .set(CORE_SCAN_COLLECTION_ITEM.DELETED_AT, Instant.now())
            .where(CORE_SCAN_COLLECTION_ITEM.APP_ID.eq(appId))
            .and(CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID.eq(collectionId))
            .and(CORE_SCAN_COLLECTION_ITEM.SCAN_RECORD_ID.`in`(scanRecordIds))
            .execute()
    }

    fun findItemsByCursor(ctx: SvcCtx, appId: UUID, collectionId: UUID, limit: Int, cursor: UUID?): Page<ScanCollectionItem> {
        var cond = CORE_SCAN_COLLECTION_ITEM.APP_ID.eq(appId)
            .and(CORE_SCAN_COLLECTION_ITEM.COLLECTION_ID.eq(collectionId))
            .and(CORE_SCAN_COLLECTION_ITEM.DELETED_AT.isNull)
        cursor?.let { cond = cond.and(CORE_SCAN_COLLECTION_ITEM.ID.lt(it)) }

        val items = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION_ITEM)
            .where(cond)
            .orderBy(CORE_SCAN_COLLECTION_ITEM.ID.desc())
            .limit(limit + 1)
            .fetchInto(ScanCollectionItem::class.java)

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
}

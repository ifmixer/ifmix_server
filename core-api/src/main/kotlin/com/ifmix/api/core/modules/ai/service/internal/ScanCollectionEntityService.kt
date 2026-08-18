package com.ifmix.api.core.modules.ai.service.internal

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.entity.ai.ScanCollectionItem
import com.ifmix.api.core.dto.ai.AddItemReq
import com.ifmix.api.core.dto.ai.AddItemRes
import com.ifmix.api.core.dto.ai.RemoveItemsReq
import com.ifmix.api.core.dto.ai.RemoveItemsRes
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.modules.ai.repo.ScanCollectionItemRepository
import com.ifmix.api.core.modules.ai.repo.ScanCollectionRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
open class ScanCollectionEntityService(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
) {
    fun createDefaultCollection(sc: SvcCtx): ScanCollection {
        val ctx = sc.op
        val now = Instant.now()
        val model = ScanCollection(id = UuidV7.generate(), appId = sc.appId!!, userId = ctx.userId,
            installId = ctx.installId, isDefault = true, createdAt = now, updatedAt = now, deletedAt = null)
        collectionRepo.insert(sc, model)
        return model
    }

    fun addItem(sc: SvcCtx, collectionId: UUID, req: AddItemReq): AddItemRes {
        val itemId = itemRepo.insertIfAbsent(sc, sc.appId!!, collectionId, req.scanRecordId)
        return AddItemRes(id = itemId)
    }

    fun removeItems(sc: SvcCtx, collectionId: UUID, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) throw com.ifmix.api.core.infra.http.ApiError(
            com.ifmix.api.core.infra.http.ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty"
        )
        val deletedCount = itemRepo.softDeleteByScanIds(sc, sc.appId!!, collectionId, req.scanRecordIds)
        return RemoveItemsRes(removed = deletedCount.toInt())
    }

    fun getDefault(sc: SvcCtx): ScanCollection? {
        val ctx = sc.op
        val appId = ctx.appId!!
        return collectionRepo.findDefault(sc, appId, ctx.installId, ctx.userId)
    }

    fun findItemsByCursor(sc: SvcCtx, collectionId: UUID, limit: Int?): Page<ScanCollectionItem> {
        val appId = sc.op.appId!!
        val effectiveLimit = limit ?: 20
        val cursor = sc.op.installId // placeholder - actual cursor logic stays simple
        return itemRepo.findItemsByCursor(sc, appId, collectionId, effectiveLimit, null)
    }
}

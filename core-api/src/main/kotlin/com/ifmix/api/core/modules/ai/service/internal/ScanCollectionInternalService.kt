package com.ifmix.api.core.modules.ai.service.internal

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.entity.scan.ScanCollection
import com.ifmix.api.core.entity.scan.ScanCollectionItem
import com.ifmix.api.core.dto.scan.AddItemReq
import com.ifmix.api.core.dto.scan.AddItemRes
import com.ifmix.api.core.dto.scan.ListItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsRes
import com.ifmix.api.core.modules.ai.repo.ScanCollectionItemRepository
import com.ifmix.api.core.modules.ai.repo.ScanCollectionRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
open class ScanCollectionInternalService(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
) {
    fun createDefaultCollection(sc: SvcCtx): ScanCollection {
        val ctx = sc.op
        val now = Instant.now()
        val model = ScanCollection(id = com.ifmix.api.core.infra.db.UuidV7.generate(), appId = sc.appId!!, userId = ctx.userId,
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

    fun getDefault(sc: SvcCtx): ScanCollection {
        val ctx = sc.op
        val appId = ctx.appId!!
        return collectionRepo.findDefault(sc, appId, ctx.installId, ctx.userId)
            ?: throw com.ifmix.api.core.infra.http.ApiError(
                com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND,
                "No default collection found"
            )
    }

    fun findItemsByCursor(opCtx: OperationContext, collectionId: UUID, limit: Int?): Page<ScanCollectionItem> {
        val sc = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)
        val appId = opCtx.appId!!
        val effectiveLimit = limit ?: 20
        val cursor = opCtx.installId // placeholder - actual cursor logic stays simple
        return itemRepo.findItemsByCursor(sc, appId, collectionId, effectiveLimit, null)
    }
}

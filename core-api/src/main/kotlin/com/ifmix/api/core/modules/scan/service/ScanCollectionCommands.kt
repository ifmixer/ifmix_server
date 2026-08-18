package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.dto.scan.AddItemReq
import com.ifmix.api.core.dto.scan.AddItemRes
import com.ifmix.api.core.dto.scan.RemoveItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsRes
import com.ifmix.api.core.model.scan.ScanCollection
import com.ifmix.api.core.modules.scan.repo.ScanCollectionItemRepository
import com.ifmix.api.core.modules.scan.repo.ScanCollectionRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
open class ScanCollectionCommands(
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
}

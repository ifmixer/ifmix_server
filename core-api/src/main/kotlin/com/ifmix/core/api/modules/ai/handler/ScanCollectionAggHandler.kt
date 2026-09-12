package com.ifmix.core.api.modules.ai.handler

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.ai.ScanCollection
import com.ifmix.core.api.entity.ai.ScanCollectionItem
import com.ifmix.core.api.dto.ai.AddItemReq
import com.ifmix.core.api.dto.ai.AddItemRes
import com.ifmix.core.api.dto.ai.RemoveItemsReq
import com.ifmix.core.api.dto.ai.RemoveItemsRes
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.modules.ai.repo.ScanCollectionItemRepository
import com.ifmix.core.api.modules.ai.repo.ScanCollectionRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
open class ScanCollectionAggHandler(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
) {
    fun createDefaultCollection(sc: ModuleCtx): ScanCollection {
        val ctx = sc.op
        val now = Instant.now()
        val id = UuidV7.generate()
        val model = ScanCollection {
            this.id = id
            this.projectId = sc.projectId!!
            this.customerId = ctx.actorId
            this.installId = ctx.installId
            this.isDefault = true
            this.createdAt = now
            this.updatedAt = now
        }
        collectionRepo.save(sc, model)
        return model
    }

    fun addItem(sc: ModuleCtx, collectionId: UUID, req: AddItemReq): AddItemRes {
        val itemId = itemRepo.insertIfAbsent(sc, sc.projectId!!, collectionId, req.scanRecordId)
        return AddItemRes(id = itemId)
    }

    fun removeItems(sc: ModuleCtx, collectionId: UUID, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty"
        )
        val deletedCount = itemRepo.softDeleteByScanIds(sc, sc.projectId!!, collectionId, req.scanRecordIds)
        return RemoveItemsRes(removed = deletedCount.toInt())
    }

    fun getDefault(sc: ModuleCtx): ScanCollection? {
        val ctx = sc.op
        val projectId = ctx.projectId!!
        return collectionRepo.findDefault(sc, projectId, ctx.actorId)
    }

    fun findItemsByCursor(sc: ModuleCtx, collectionId: UUID, limit: Int?): Page<ScanCollectionItem> {
        val projectId = sc.op.projectId!!
        val effectiveLimit = limit ?: 20
        return itemRepo.findItemsByCursor(sc, projectId, collectionId, effectiveLimit, null)
    }
}

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
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
open class ScanCollectionAggHandler(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
    private val scanRepo: ScanRecordRepository,
) {
    fun createDefaultCollection(sc: ModuleCtx): ScanCollection {
        val ctx = sc.action
        val now = Instant.now()
        val id = UuidV7.generate()
        val model = ScanCollection {
            this.id = id
            this.projectId = sc.projectId!!
            this.customerId = ctx.actorId
            this.installId = ctx.mustGetTokenInstallId()
            this.isDefault = true
            this.createdAt = now
            this.updatedAt = now
        }
        collectionRepo.save(sc, model)
        return model
    }

    fun addItem(sc: ModuleCtx, collectionId: UUID, req: AddItemReq): AddItemRes {
        val projectId = sc.projectId!!
        val customerId = sc.action.mustGetActorId()
        // collection 必须属于当前 customer；scan 必须属于当前 customer。跨用户统一 NOT_FOUND。
        requireOwnedCollection(sc, projectId, customerId, collectionId)
        if (!scanRepo.existsOwned(sc, projectId, customerId, req.scanRecordId)) throw notFound()
        val itemId = itemRepo.insertIfAbsent(sc, projectId, collectionId, req.scanRecordId)
        return AddItemRes(id = itemId)
    }

    fun removeItems(sc: ModuleCtx, collectionId: UUID, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty"
        )
        val projectId = sc.projectId!!
        val customerId = sc.action.mustGetActorId()
        requireOwnedCollection(sc, projectId, customerId, collectionId)
        val deletedCount = itemRepo.softDeleteByScanIds(sc, projectId, collectionId, req.scanRecordIds)
        return RemoveItemsRes(removed = deletedCount)
    }

    fun getDefault(sc: ModuleCtx): ScanCollection? {
        val ctx = sc.action
        val projectId = ctx.projectId!!
        return collectionRepo.findDefault(sc, projectId, ctx.actorId)
    }

    fun findItemsByCursor(sc: ModuleCtx, collectionId: UUID, limit: Int?): Page<ScanCollectionItem> {
        val projectId = sc.action.projectId!!
        val customerId = sc.action.mustGetActorId()
        requireOwnedCollection(sc, projectId, customerId, collectionId)
        val effectiveLimit = limit ?: 20
        return itemRepo.findItemsByCursor(sc, projectId, collectionId, effectiveLimit, null)
    }

    /** collection 归属校验：非本人拥有（或不存在）统一 NOT_FOUND，不泄露存在性。 */
    private fun requireOwnedCollection(sc: ModuleCtx, projectId: String, customerId: UUID, collectionId: UUID) {
        if (!collectionRepo.existsOwned(sc, projectId, customerId, collectionId)) throw notFound()
    }

    private fun notFound() = com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
}

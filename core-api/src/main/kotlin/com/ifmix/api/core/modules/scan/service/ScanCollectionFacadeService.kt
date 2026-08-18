package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.model.scan.ScanCollection
import com.ifmix.api.core.model.scan.ScanCollectionItem
import com.ifmix.api.core.dto.scan.AddItemReq
import com.ifmix.api.core.dto.scan.AddItemRes
import com.ifmix.api.core.dto.scan.ListItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsRes
import com.ifmix.api.core.modules.scan.repo.ScanCollectionItemRepository
import com.ifmix.api.core.modules.scan.repo.ScanCollectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
open class ScanCollectionFacadeService(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    @Transactional
    fun getDefault(ctx: OperationContext): ScanCollection {
        val appId = ctx.appId!!
        val sc = svc(ctx)
        return collectionRepo.findDefault(sc, appId, ctx.installId, ctx.userId)
            ?: createDefaultCollection(ctx, appId)
    }

    private fun createDefaultCollection(ctx: OperationContext, appId: UUID): ScanCollection {
        val now = Instant.now()
        val model = ScanCollection(id = UuidV7.generate(), appId = appId, userId = ctx.userId,
            installId = ctx.installId, isDefault = true, createdAt = now, updatedAt = now, deletedAt = null)
        collectionRepo.insert(svc(ctx), model)
        return model
    }

    @Transactional
    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes {
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val itemId = itemRepo.insertIfAbsent(svc(ctx), ctx.appId!!, collectionId, req.scanRecordId)
        return AddItemRes(id = itemId)
    }

    @Transactional
    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) throw ApiError(ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty")
        val appId = ctx.appId!!
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val deletedCount = itemRepo.softDeleteByScanIds(svc(ctx), appId, collectionId, req.scanRecordIds)
        return RemoveItemsRes(removed = deletedCount.toInt())
    }

    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItem> {
        val appId = ctx.appId!!
        val collectionId = req?.collectionId ?: getDefault(ctx).id
        val limit = req?.limit ?: 20
        val cursor = req?.cursor?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }
        return itemRepo.findItemsByCursor(svc(ctx), appId, collectionId, limit, cursor)
    }
}

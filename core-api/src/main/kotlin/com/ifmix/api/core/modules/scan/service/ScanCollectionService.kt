package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.entity.collection.ScanCollection
import com.ifmix.api.core.entity.collection.dto.ScanCollectionItemView
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.modules.scan.dto.AddItemReq
import com.ifmix.api.core.modules.scan.dto.AddItemRes
import com.ifmix.api.core.modules.scan.dto.ListItemsReq
import com.ifmix.api.core.modules.scan.dto.RemoveItemsReq
import com.ifmix.api.core.modules.scan.dto.RemoveItemsRes
import com.ifmix.api.core.modules.scan.repo.ScanCollectionRepository
import com.ifmix.api.core.modules.scan.repo.ScanCollectionItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
open class ScanCollectionService(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
) {

    @Transactional
    fun getDefault(ctx: OperationContext): ScanCollection {
        val appId = ctx.mustGetAppId()
        val userId = ctx.userId
        val installId = ctx.installId

        return collectionRepo.findDefault(ctx.repoCtx, appId, installId, userId)
            ?: createDefaultCollection(ctx, appId, userId, installId)
    }

    private fun createDefaultCollection(
        ctx: OperationContext,
        appId: UUID,
        userId: UUID?,
        installId: UUID?,
    ): ScanCollection {
        val now = Instant.now()
        val entity = ScanCollection {
            id = UuidV7.generate()
            this.appId = appId
            this.userId = userId
            this.installId = installId
            this.isDefault = true
            createdAt = now
            updatedAt = now
            deletedAt = null
        }
        return collectionRepo.save(ctx.repoCtx, entity)
    }

    @Transactional
    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes {
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val itemId = itemRepo.insertIfAbsent(ctx.repoCtx, ctx.mustGetAppId(), collectionId, req.scanRecordId)
        return AddItemRes(id = itemId)
    }

    @Transactional
    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty")
        }
        val appId = ctx.mustGetAppId()
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val deletedCount = itemRepo.softDeleteByScanIds(ctx.repoCtx, appId, collectionId, req.scanRecordIds)
        return RemoveItemsRes(removed = deletedCount.toInt())
    }

    @Transactional(readOnly = true)
    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItemView> {
        val appId = ctx.mustGetAppId()
        val collectionId = req?.collectionId ?: getDefault(ctx).id
        val limit = req?.limit ?: 20
        val cursor = req?.cursor?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }
        return itemRepo.findItemsByCursor(ctx.repoCtx, appId, collectionId, limit, cursor)
    }
}

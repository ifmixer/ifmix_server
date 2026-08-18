package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.jooq.TxRunner
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
import java.time.Instant
import java.util.UUID

@Service
open class ScanCollectionFacadeService(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun getDefault(ctx: OperationContext): ScanCollection = tx.withTx(svc(ctx)) { sc ->
        val appId = ctx.appId!!
        collectionRepo.findDefault(sc, appId, ctx.installId, ctx.userId)
            ?: createDefaultCollection(sc)
    }

    private fun createDefaultCollection(sc: SvcCtx): ScanCollection {
        val ctx = sc.op
        val now = Instant.now()
        val model = ScanCollection(id = UuidV7.generate(), appId = sc.appId!!, userId = ctx.userId,
            installId = ctx.installId, isDefault = true, createdAt = now, updatedAt = now, deletedAt = null)
        collectionRepo.insert(sc, model)
        return model
    }

    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes = tx.withTx(svc(ctx)) { sc ->
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val itemId = itemRepo.insertIfAbsent(sc, ctx.appId!!, collectionId, req.scanRecordId)
        AddItemRes(id = itemId)
    }

    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes = tx.withTx(svc(ctx)) { sc ->
        if (req.scanRecordIds.isEmpty()) throw ApiError(ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty")
        val appId = ctx.appId!!
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val deletedCount = itemRepo.softDeleteByScanIds(sc, appId, collectionId, req.scanRecordIds)
        RemoveItemsRes(removed = deletedCount.toInt())
    }

    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItem> {
        val appId = ctx.appId!!
        val collectionId = req?.collectionId ?: getDefault(ctx).id
        val limit = req?.limit ?: 20
        val cursor = req?.cursor?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }
        return itemRepo.findItemsByCursor(svc(ctx), appId, collectionId, limit, cursor)
    }
}

package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.modules.ai.service.internal.ScanCollectionInternalService
import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.entity.ai.ScanCollectionItem
import com.ifmix.api.core.dto.ai.AddItemReq
import com.ifmix.api.core.dto.ai.AddItemRes
import com.ifmix.api.core.dto.ai.ListItemsReq
import com.ifmix.api.core.dto.ai.RemoveItemsReq
import com.ifmix.api.core.dto.ai.RemoveItemsRes
import org.springframework.stereotype.Service

@Service
open class ScanCollectionFacadeService(
    private val svcCtxFactory: SvcCtxFactory,
    private val internalService: ScanCollectionInternalService,
    private val tx: TxRunner,
) {
    fun getDefault(ctx: OperationContext): ScanCollection = tx.withTx(svcCtxFactory.forApp(ctx)) { sc ->
        internalService.getDefault(sc) ?: internalService.createDefaultCollection(sc)
    }

    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes = tx.withTx(svcCtxFactory.forApp(ctx)) { sc ->
        val collectionId = req.collectionId ?: getOrCreateDefault(sc).id
        internalService.addItem(sc, collectionId, req)
    }

    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes = tx.withTx(svcCtxFactory.forApp(ctx)) { sc ->
        val collectionId = req.collectionId ?: getOrCreateDefault(sc).id
        internalService.removeItems(sc, collectionId, req)
    }

    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItem> {
        val sc = svcCtxFactory.forApp(ctx)
        val collectionId = req?.collectionId ?: (internalService.getDefault(sc)?.id
            ?: return Page(items = emptyList(), nextCursor = null, hasMore = false))
        val limit = req?.limit
        return internalService.findItemsByCursor(sc, collectionId, limit)
    }

    /** 事务内获取或创建默认收藏夹——复用调用方传入的 SvcCtx，避免开新事务。 */
    private fun getOrCreateDefault(sc: SvcCtx): ScanCollection =
        internalService.getDefault(sc) ?: internalService.createDefaultCollection(sc)
}

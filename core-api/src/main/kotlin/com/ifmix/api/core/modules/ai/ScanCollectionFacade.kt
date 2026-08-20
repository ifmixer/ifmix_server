package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.tx.TxRunner
import com.ifmix.api.core.modules.ai.handler.ScanCollectionHandler
import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.entity.ai.ScanCollectionItem
import com.ifmix.api.core.dto.ai.AddItemReq
import com.ifmix.api.core.dto.ai.AddItemRes
import com.ifmix.api.core.dto.ai.ListItemsReq
import com.ifmix.api.core.dto.ai.RemoveItemsReq
import com.ifmix.api.core.dto.ai.RemoveItemsRes
import org.springframework.stereotype.Service

@Service
open class ScanCollectionFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: ScanCollectionHandler,
    private val tx: TxRunner,
) {
    fun getDefault(ctx: OperationContext): ScanCollection = tx.withTx(mcFactory.forApp(ctx)) { sc ->
        handler.getDefault(sc) ?: handler.createDefaultCollection(sc)
    }

    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes = tx.withTx(mcFactory.forApp(ctx)) { sc ->
        val collectionId = req.collectionId ?: getOrCreateDefault(sc).id
        handler.addItem(sc, collectionId, req)
    }

    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes = tx.withTx(mcFactory.forApp(ctx)) { sc ->
        val collectionId = req.collectionId ?: getOrCreateDefault(sc).id
        handler.removeItems(sc, collectionId, req)
    }

    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItem> {
        val sc = mcFactory.forApp(ctx)
        val collectionId = req?.collectionId ?: (handler.getDefault(sc)?.id
            ?: return Page(items = emptyList(), nextCursor = null, hasMore = false))
        val limit = req?.limit
        return handler.findItemsByCursor(sc, collectionId, limit)
    }

    /** 事务内获取或创建默认收藏夹——复用调用方传入的 ModuleCtx，避免开新事务。 */
    private fun getOrCreateDefault(sc: ModuleCtx): ScanCollection =
        handler.getDefault(sc) ?: handler.createDefaultCollection(sc)
}

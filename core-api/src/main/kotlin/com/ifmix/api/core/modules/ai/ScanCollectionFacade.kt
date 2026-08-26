package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.ai.handler.ScanCollectionAggHandler
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
    private val handler: ScanCollectionAggHandler,
) {
    fun getDefault(ctx: OperationContext): ScanCollection =
        handler.getDefault(mcFactory.forApp(ctx))
            ?: handler.createDefaultCollection(mcFactory.forApp(ctx))

    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes {
        val mc = mcFactory.forApp(ctx)
        val collectionId = req.collectionId ?: (handler.getDefault(mc)?.id
            ?: handler.createDefaultCollection(mc).id)
        return handler.addItem(mc, collectionId, req)
    }

    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes {
        val mc = mcFactory.forApp(ctx)
        val collectionId = req.collectionId ?: (handler.getDefault(mc)?.id
            ?: handler.createDefaultCollection(mc).id)
        return handler.removeItems(mc, collectionId, req)
    }

    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItem> {
        val mc = mcFactory.forApp(ctx)
        val collectionId = req?.collectionId ?: (handler.getDefault(mc)?.id
            ?: return Page(items = emptyList()))
        return handler.findItemsByCursor(mc, collectionId, req?.limit)
    }
}

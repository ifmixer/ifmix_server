package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.ai.handler.ScanCollectionAggHandler
import com.ifmix.core.api.entity.ai.ScanCollection
import com.ifmix.core.api.entity.ai.ScanCollectionItem
import com.ifmix.core.api.dto.ai.AddItemReq
import com.ifmix.core.api.dto.ai.AddItemRes
import com.ifmix.core.api.dto.ai.ListItemsReq
import com.ifmix.core.api.dto.ai.RemoveItemsReq
import com.ifmix.core.api.dto.ai.RemoveItemsRes
import org.springframework.stereotype.Service

@Service
open class ScanCollectionFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: ScanCollectionAggHandler,
) {
    fun getDefault(ctx: OperationContext): ScanCollection =
        handler.getDefault(mcFactory.forProject(ctx))
            ?: handler.createDefaultCollection(mcFactory.forProject(ctx))

    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes {
        val mc = mcFactory.forProject(ctx)
        val collectionId = req.collectionId ?: (handler.getDefault(mc)?.id
            ?: handler.createDefaultCollection(mc).id)
        return handler.addItem(mc, collectionId, req)
    }

    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes {
        val mc = mcFactory.forProject(ctx)
        val collectionId = req.collectionId ?: (handler.getDefault(mc)?.id
            ?: handler.createDefaultCollection(mc).id)
        return handler.removeItems(mc, collectionId, req)
    }

    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItem> {
        val mc = mcFactory.forProject(ctx)
        val collectionId = req?.collectionId ?: (handler.getDefault(mc)?.id
            ?: return Page(items = emptyList()))
        return handler.findItemsByCursor(mc, collectionId, req?.limit)
    }
}

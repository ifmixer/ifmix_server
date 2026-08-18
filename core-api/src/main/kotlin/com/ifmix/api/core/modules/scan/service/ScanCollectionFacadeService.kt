package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.entity.scan.ScanCollection
import com.ifmix.api.core.entity.scan.ScanCollectionItem
import com.ifmix.api.core.dto.scan.AddItemReq
import com.ifmix.api.core.dto.scan.AddItemRes
import com.ifmix.api.core.dto.scan.ListItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsRes
import org.springframework.stereotype.Service

@Service
class ScanCollectionFacadeService(
    private val internalService: ScanCollectionInternalService,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun getDefault(ctx: OperationContext): ScanCollection = tx.withTx(svc(ctx)) { sc ->
        internalService.getDefault(sc) ?: internalService.createDefaultCollection(sc)
    }

    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes = tx.withTx(svc(ctx)) { sc ->
        val collectionId = req.collectionId ?: getDefault(ctx).id
        internalService.addItem(sc, collectionId, req)
    }

    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes = tx.withTx(svc(ctx)) { sc ->
        val collectionId = req.collectionId ?: getDefault(ctx).id
        internalService.removeItems(sc, collectionId, req)
    }

    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItem> {
        val collectionId = req?.collectionId ?: getDefault(ctx).id
        val limit = req?.limit
        return internalService.findItemsByCursor(svc(ctx), collectionId, limit)
    }
}

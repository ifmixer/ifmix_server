package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.model.scan.ScanCollection
import com.ifmix.api.core.model.scan.ScanCollectionItem
import com.ifmix.api.core.dto.scan.ListItemsReq
import com.ifmix.api.core.modules.scan.repo.ScanCollectionItemRepository
import com.ifmix.api.core.modules.scan.repo.ScanCollectionRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
open class ScanCollectionQueries(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun getDefault(sc: SvcCtx): ScanCollection {
        val ctx = sc.op
        val appId = ctx.appId!!
        return collectionRepo.findDefault(sc, appId, ctx.installId, ctx.userId)
            ?: throw com.ifmix.api.core.infra.http.ApiError(
                com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND,
                "No default collection found"
            )
    }

    fun findItemsByCursor(opCtx: OperationContext, collectionId: UUID, limit: Int?): Page<ScanCollectionItem> {
        val sc = svc(opCtx)
        val appId = opCtx.appId!!
        val effectiveLimit = limit ?: 20
        val cursor = opCtx.installId // placeholder - actual cursor logic stays simple
        return itemRepo.findItemsByCursor(sc, appId, collectionId, effectiveLimit, null)
    }
}

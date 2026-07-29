package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.entity.antique.ScanRecord
import com.ifmix.api.core.common.jimmer.repository.collection.CollectionRepository
import com.ifmix.api.core.common.jimmer.repository.collection.CollectionItemRepository

/**
 * 收藏业务编排（简化版 - 占位）。
 */
class CollectionService(
    private val collectionRepo: CollectionRepository,
    private val itemRepo: CollectionItemRepository,
) {

    fun getDefault(ctx: RequestContext): com.ifmix.api.core.common.jimmer.entity.collection.Collection =
        throw NotImplementedError("getDefault not implemented")

    fun addItem(ctx: RequestContext, req: Any): String =
        throw NotImplementedError("addItem not implemented")

    fun removeItems(ctx: RequestContext, req: Any): Long =
        throw NotImplementedError("removeItems not implemented")

    fun listItems(ctx: RequestContext, req: Any): Page<ScanRecord> =
        Page(emptyList(), null, false)
}

// Stub DTOs to satisfy compiler
data class AddItemReq(val collectionId: String?, val scanRecordId: String?)
data class RemoveItemsReq(val collectionId: String?, val scanRecordIds: List<String>?)
data class ListItemsReq(val collectionId: String?, val limit: Int?, val cursor: String?)

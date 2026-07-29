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

    fun addItem(ctx: RequestContext, req: AddItemReq): String =
        throw NotImplementedError("addItem not implemented")

    fun removeItems(ctx: RequestContext, req: RemoveItemsReq): Long =
        throw NotImplementedError("removeItems not implemented")

    fun listItems(ctx: RequestContext, req: ListItemsReq?): Page<ScanRecord> =
        Page(emptyList(), null, false)
}

// Request/Response DTOs
data class AddItemReq(val collectionId: String? = null, val scanRecordId: String? = null)
data class AddItemRes(val itemId: String)

data class RemoveItemsReq(val collectionId: String? = null, val scanRecordIds: List<String>? = null)
data class RemoveItemsRes(val removed: Long)

data class ListItemsReq(val collectionId: String? = null, val limit: Int? = null, val cursor: String? = null)

data class GetDefaultRes(val id: String, val isDefault: Boolean)

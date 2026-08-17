package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.CollectionItemConnection
import com.ifmix.api.core.graphql.generated.types.CollectionItemType
import com.ifmix.api.core.graphql.generated.types.Collection
import com.ifmix.api.core.modules.antique.toScanRecord
import com.ifmix.api.core.modules.collection.AddItemReq
import com.ifmix.api.core.modules.collection.ListItemsReq
import com.ifmix.api.core.modules.collection.RemoveItemsReq
import com.ifmix.api.core.modules.collection.toCollectionItemType
import com.ifmix.api.core.modules.collection.toCollection
import com.ifmix.api.core.modules.collection.CollectionService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

@DgsComponent
class CustomerCollectionFetcher(
    private val collectionService: CollectionService,
) {

    @DgsQuery(field = "collection_getDefault")
    fun defaultCollection(dfe: DgsDataFetchingEnvironment): Collection {
        val ctx = getContext(dfe)
        return collectionService.getDefault(ctx).toCollection()
    }

    @DgsQuery(field = "collectionItem_list")
    fun collectionItems(
        @InputArgument collectionId: String?,
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        dfe: DgsDataFetchingEnvironment,
    ): CollectionItemConnection {
        val ctx = getContext(dfe)
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val req = ListItemsReq(collectionId = collectionId, cursor = cursor, limit = effectiveLimit)
        val (items, scanMap, hasMore) = collectionService.listItemsWithRecords(ctx, req)

        return CollectionItemConnection(
            items = items.map { item ->
                val scanRecord = item.scanRecordId?.let { scanMap[it.toHexString()] }?.toScanRecord()
                item.toCollectionItemType(scanRecord)
            },
            nextCursor = if (items.isNotEmpty()) items.last().id?.toHexString() else null,
            hasMore = hasMore,
        )
    }

    @DgsMutation(field = "collectionItem_add")
    fun addCollectionItem(
        @InputArgument collectionId: String?,
        @InputArgument scanRecordId: String,
        dfe: DgsDataFetchingEnvironment,
    ): String {
        val ctx = getContext(dfe)
        val req = AddItemReq(collectionId = collectionId, scanRecordId = scanRecordId)
        return collectionService.addItem(ctx, req)
    }

    @DgsMutation(field = "collectionItem_remove")
    fun removeCollectionItems(
        @InputArgument collectionId: String?,
        @InputArgument scanRecordIds: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): Int {
        val ctx = getContext(dfe)
        val req = RemoveItemsReq(collectionId = collectionId, scanRecordIds = scanRecordIds)
        return collectionService.removeItems(ctx, req).toInt()
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): RequestContext =
        DgsContext.getCustomContext(dfe)
}

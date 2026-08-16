package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.CollectionItemConnection
import com.ifmix.api.core.graphql.common.type.CollectionType
import com.ifmix.api.core.modules.antique.toScanRecordType
import com.ifmix.api.core.modules.collection.AddItemReq
import com.ifmix.api.core.modules.collection.ListItemsReq
import com.ifmix.api.core.modules.collection.RemoveItemsReq
import com.ifmix.api.core.modules.collection.toCollectionItemType
import com.ifmix.api.core.modules.collection.toCollectionType
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

    @DgsQuery
    fun defaultCollection(dfe: DgsDataFetchingEnvironment): CollectionType {
        val ctx = getContext(dfe)
        return collectionService.getDefault(ctx.requestContext).toCollectionType()
    }

    @DgsQuery
    fun collectionItems(
        @InputArgument collectionId: String?,
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        dfe: DgsDataFetchingEnvironment,
    ): CollectionItemConnection {
        val ctx = getContext(dfe)
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val req = ListItemsReq(collectionId = collectionId, cursor = cursor, limit = effectiveLimit)
        val page = collectionService.listItems(ctx.requestContext, req)

        return CollectionItemConnection(
            items = page.items.map { it.toScanRecordType() }.map { scanType ->
                com.ifmix.api.core.graphql.common.type.CollectionItemType(
                    id = scanType.id,
                    scanRecord = scanType,
                )
            },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation
    fun addCollectionItem(
        @InputArgument collectionId: String?,
        @InputArgument scanRecordId: String,
        dfe: DgsDataFetchingEnvironment,
    ): String {
        val ctx = getContext(dfe)
        val req = AddItemReq(collectionId = collectionId, scanRecordId = scanRecordId)
        return collectionService.addItem(ctx.requestContext, req)
    }

    @DgsMutation
    fun removeCollectionItems(
        @InputArgument collectionId: String?,
        @InputArgument scanRecordIds: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): Int {
        val ctx = getContext(dfe)
        val req = RemoveItemsReq(collectionId = collectionId, scanRecordIds = scanRecordIds)
        return collectionService.removeItems(ctx.requestContext, req).toInt()
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): GraphQLRequestContext =
        DgsContext.getCustomContext(dfe)
}

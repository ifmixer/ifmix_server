package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.CollectionItemConnection
import com.ifmix.api.core.graphql.common.type.CollectionItemType
import com.ifmix.api.core.graphql.common.type.CollectionType
import com.ifmix.api.core.graphql.common.type.ScanRecordType
import com.ifmix.api.core.modules.antique.AntiqueService
import com.ifmix.api.core.modules.antique.toScanRecordType
import com.ifmix.api.core.modules.collection.AddItemReq
import com.ifmix.api.core.modules.collection.ListItemsReq
import com.ifmix.api.core.modules.collection.RemoveItemsReq
import com.ifmix.api.core.modules.collection.toCollectionItemType
import com.ifmix.api.core.modules.collection.toCollectionType
import com.ifmix.api.core.modules.collection.CollectionItemDocument
import com.ifmix.api.core.modules.collection.CollectionItemRepository
import com.ifmix.api.core.modules.collection.CollectionService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

@DgsComponent
class CustomerCollectionFetcher(
    private val collectionService: CollectionService,
    private val antiqueService: AntiqueService,
    private val itemRepo: CollectionItemRepository,
    private val mongo: MongoTemplate,
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
        val rawItems = loadCollectionItems(ctx.requestContext, collectionId, cursor, effectiveLimit)

        val itemScanIds = rawItems.mapNotNull { it.scanRecordId?.toHexString() }.distinct()
        val scanRecords = if (itemScanIds.isNotEmpty()) {
            itemScanIds.associateWith { id ->
                loadScanRecordType(ctx.requestContext, id)
            }
        } else emptyMap<String, ScanRecordType>()

        return CollectionItemConnection(
            items = rawItems.map { it.toCollectionItemType(scanRecords[it.scanRecordId?.toHexString()]) },
            nextCursor = if (rawItems.isNotEmpty()) rawItems.last().id else null,
            hasMore = rawItems.size >= effectiveLimit,
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

    /**
     * Load collection items directly from the database for GraphQL response.
     * Returns items in descending _id order for cursor-based pagination.
     */
    private fun loadCollectionItems(
        ctx: RequestContext,
        collectionId: String?,
        cursor: String?,
        limit: Int,
    ): List<CollectionItemDocument> {
        val resolvedCid = resolveCollectionId(ctx, collectionId) ?: return emptyList()
        val query = Query(
            Criteria.where("appId").`is`(ctx.appId)
                .and("collectionId").`is`(resolvedCid)
                .and("deletedAt").`is`(null),
        )
        if (!cursor.isNullOrBlank() && ObjectId.isValid(cursor)) {
            query.addCriteria(Criteria.where("_id").lt(ObjectId(cursor)))
        }
        query.with(
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "_id",
            ),
        )
        query.limit(limit + 1)
        return mongo.find(query, CollectionItemDocument::class.java)
    }

    private fun resolveCollectionId(ctx: RequestContext, collectionId: String?): String? {
        return if (collectionId != null) {
            collectionService.getDefault(ctx).id // service validates via resolveCollectionId internally
            collectionId
        } else {
            collectionService.getDefault(ctx).id
        }
    }

    private fun loadScanRecordType(ctx: RequestContext, id: String): ScanRecordType? {
        return try {
            antiqueService.getScanRecordById(id).toScanRecordType()
        } catch (_: Exception) {
            null
        }
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): GraphQLRequestContext =
        DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
}

package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.CollectionItemConnection
import com.ifmix.api.core.graphql.generated.types.CollectionItemType
import com.ifmix.api.core.graphql.generated.types.Collection
import com.ifmix.api.core.graphql.generated.types.PresignDownloadResult
import com.ifmix.api.core.graphql.generated.types.PresignUploadResult
import com.ifmix.api.core.graphql.generated.types.ScanConnection
import com.ifmix.api.core.graphql.generated.types.ScanRecord
import com.ifmix.api.core.modules.ai.AiFacade
import com.ifmix.api.core.modules.ai.CreateScanRequest
import com.ifmix.api.core.modules.ai.entity.toScanRecord
import com.ifmix.api.core.modules.ai.entity.toCollection
import com.ifmix.api.core.modules.ai.entity.toCollectionItemType
import com.ifmix.api.core.modules.ai.entity.CollectionItemEntity
import com.ifmix.api.core.graphql.generated.types.FilterGroup
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Duration
import java.util.UUID

@DgsComponent
class AiFetcher(
    private val aiFacade: AiFacade,
) {

    // ---- scan queries ----

    @DgsQuery(field = "query_ai_findScanById")
    fun findScanById(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): ScanRecord {
        val ctx = getContext(dfe)
        val doc = aiFacade.getScanRecordById(ctx, id)
        return doc.toScanRecord()
    }

    @DgsQuery(field = "query_ai_listScans")
    fun listScans(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        @InputArgument collected: Boolean?,
        @InputArgument filter: FilterGroup?,
        dfe: DgsDataFetchingEnvironment,
    ): ScanConnection {
        val ctx = getContext(dfe)
        val page = aiFacade.findByCursor(ctx, cursor, limit, collected, filter)
        return ScanConnection(
            items = page.items.map { it.toScanRecord() },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    // ---- collection queries ----

    @DgsQuery(field = "query_ai_getDefaultCollection")
    fun getDefaultCollection(dfe: DgsDataFetchingEnvironment): Collection {
        val ctx = getContext(dfe)
        return aiFacade.getDefaultCollection(ctx).toCollection()
    }

    @DgsQuery(field = "query_ai_listCollectionItems")
    fun listCollectionItems(
        @InputArgument collectionId: String?,
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        dfe: DgsDataFetchingEnvironment,
    ): CollectionItemConnection {
        val ctx = getContext(dfe)
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val (items, scanMap, hasMore) = aiFacade.listItemsWithRecords(ctx, collectionId, cursor, effectiveLimit)

        return CollectionItemConnection(
            items = items.map { item: CollectionItemEntity ->
                val scanRecord = item.scanRecordId?.toHexString()?.let { scanMap[it] }?.toScanRecord()
                item.toCollectionItemType(scanRecord)
            },
            nextCursor = if (items.isNotEmpty()) items.last().id!!.toHexString() else null,
            hasMore = hasMore,
        )
    }

    // ---- scan mutations ----

    @DgsMutation(field = "mutation_ai_createScan")
    fun createScan(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): ScanRecord {
        val ctx = getContext(dfe)
        val imageUrl = input["imageUrl"] as String
        val relatedId = input["relatedId"] as? String
        val request = CreateScanRequest(imageUrl = imageUrl, relatedId = relatedId)
        val id = aiFacade.createScan(ctx, request)
        return aiFacade.getScanRecordById(ctx, id).toScanRecord()
    }

    @DgsMutation(field = "mutation_ai_updateScan")
    fun updateScan(
        @InputArgument id: String,
        @InputArgument collected: Boolean,
        dfe: DgsDataFetchingEnvironment,
    ): ScanRecord {
        val ctx = getContext(dfe)
        aiFacade.markCollected(ctx, id, collected)
        return aiFacade.getScanRecordById(ctx, id).toScanRecord()
    }

    @DgsMutation(field = "mutation_ai_deleteScan")
    fun deleteScan(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        return aiFacade.deleteScan(ctx, id)
    }

    // ---- collection mutations ----

    @DgsMutation(field = "mutation_ai_addCollectionItem")
    fun addCollectionItem(
        @InputArgument collectionId: String?,
        @InputArgument scanRecordId: String,
        dfe: DgsDataFetchingEnvironment,
    ): String {
        val ctx = getContext(dfe)
        return aiFacade.addItem(ctx, collectionId, scanRecordId)
    }

    @DgsMutation(field = "mutation_ai_removeCollectionItems")
    fun removeCollectionItems(
        @InputArgument collectionId: String?,
        @InputArgument scanRecordIds: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): Int {
        val ctx = getContext(dfe)
        return aiFacade.removeItems(ctx, collectionId, scanRecordIds).toInt()
    }

    // ---- storage mutations（保留，原在 CustomerScanFetcher 中）----

    @DgsMutation(field = "storage_presignUpload")
    fun presignUpload(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): PresignUploadResult {
        val ctx = getContext(dfe)
        val category = input["category"] as String
        val contentType = input["contentType"] as String
        val objectKey = "${category}/${UUID.randomUUID()}.png"
        val uploadUrl = aiFacade.presignedUploadUrl(objectKey, contentType, Duration.ofMinutes(5))
        val downloadUrl = when {
            category == "scan" -> uploadUrl
            else -> aiFacade.presignedDownloadUrl(objectKey, Duration.ofHours(1))
        }
        return PresignUploadResult(
            mediaId = objectKey,
            uploadUrl = uploadUrl,
            imageKey = objectKey,
            downloadUrl = downloadUrl,
        )
    }

    @DgsMutation(field = "storage_presignDownload")
    fun presignDownload(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): PresignDownloadResult {
        val ctx = getContext(dfe)
        val imageKey = input["imageKey"] as String
        val durationSeconds = input["durationSeconds"] as? Int ?: 3600
        val downloadUrl = aiFacade.presignedDownloadUrl(imageKey, Duration.ofSeconds(durationSeconds.toLong()))
        return PresignDownloadResult(downloadUrl = downloadUrl)
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): RequestContext =
        DgsContext.getCustomContext(dfe)
}

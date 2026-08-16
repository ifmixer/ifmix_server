package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.generated.types.PresignDownloadResult
import com.ifmix.api.core.graphql.generated.types.PresignUploadResult
import com.ifmix.api.core.graphql.generated.types.ScanConnection
import com.ifmix.api.core.graphql.generated.types.ScanRecord
import com.ifmix.api.core.modules.antique.AntiqueService
import com.ifmix.api.core.modules.antique.CreateScanRequest
import com.ifmix.api.core.modules.antique.toScanRecord
import com.ifmix.api.core.modules.antique.ScanRecordDocument
import com.ifmix.api.core.modules.antique.ScanRecordRepository
import com.ifmix.api.core.modules.storage.UploadRecordDocument
import com.ifmix.api.core.modules.storage.UploadRecordRepo
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Duration
import java.util.UUID

/** Map field from GraphQL input to ScanRecordDocument patch. */
private fun mapCollectedPatch(collected: Boolean): Map<String, Any?> =
    mapOf("collected" to collected, "updatedAt" to java.time.Instant.now())

@DgsComponent
class CustomerScanFetcher(
    private val antiqueService: AntiqueService,
    private val scanRecordRepo: ScanRecordRepository,
    private val uploadRecordRepo: UploadRecordRepo,
) {

    @DgsQuery
    fun scanRecord(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): ScanRecord? {
        val ctx = getContext(dfe)
        val doc = antiqueService.getScanRecordById(id)
        return doc.toScanRecord()
    }

    @DgsQuery
    fun scanRecords(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        @InputArgument collected: Boolean?,
        dfe: DgsDataFetchingEnvironment,
    ): ScanConnection {
        val ctx = getContext(dfe)
        val input = CursorQueryInput(cursor = cursor, limit = limit)
        val page = antiqueService.findByCursor(ctx.requestContext, input)
        return ScanConnection(
            items = page.items.map { it.toScanRecord() },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation
    fun newScan(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): ScanRecord {
        val ctx = getContext(dfe)
        val imageUrl = input["imageUrl"] as String
        val relatedId = input["relatedId"] as? String
        val request = CreateScanRequest(imageUrl = imageUrl, relatedId = relatedId)
        val id = antiqueService.createScan(ctx.requestContext, request)
        return antiqueService.getScanRecordById(id).toScanRecord()
    }

    @DgsMutation
    fun updateScan(
        @InputArgument id: String,
        @InputArgument collected: Boolean,
        dfe: DgsDataFetchingEnvironment,
    ): Boolean {
        val ctx = getContext(dfe)
        return scanRecordRepo.updateById(ctx.requestContext, id, mapCollectedPatch(collected))
    }

    @DgsMutation
    fun deleteScan(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        return scanRecordRepo.deleteById(ctx.requestContext, id)
    }

    @DgsMutation
    fun presignUpload(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): PresignUploadResult {
        val ctx = getContext(dfe)
        val category = input["category"] as String
        val contentType = input["contentType"] as String
        val objectKey = "${category}/${UUID.randomUUID()}.png"
        val uploadUrl = antiqueService.presignedUploadUrl(objectKey, contentType, Duration.ofMinutes(5))
        val downloadUrl = when {
            category == "scan" -> uploadUrl
            else -> antiqueService.presignedDownloadUrl(objectKey, Duration.ofHours(1))
        }
        recordUpload(ctx.requestContext, objectKey, contentType, category)
        return PresignUploadResult(
            mediaId = objectKey,
            uploadUrl = uploadUrl,
            imageKey = objectKey,
            downloadUrl = downloadUrl,
        )
    }

    @DgsMutation
    fun presignDownload(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): PresignDownloadResult {
        val ctx = getContext(dfe)
        val imageKey = input["imageKey"] as String
        val durationSeconds = input["durationSeconds"] as? Int ?: 3600
        val downloadUrl =
            antiqueService.presignedDownloadUrl(imageKey, Duration.ofSeconds(durationSeconds.toLong()))
        return PresignDownloadResult(downloadUrl = downloadUrl)
    }

    private fun recordUpload(
        ctx: RequestContext,
        objectKey: String,
        contentType: String,
        category: String,
    ) {
        try {
            val doc = UploadRecordDocument().apply {
                this.appId = ctx.appId
                this.installId = ctx.installId
                this.userId = ctx.userId
                this.objectKey = objectKey
                this.contentType = contentType
                this.category = category
                createdAt = java.time.Instant.now()
                updatedAt = java.time.Instant.now()
            }
            uploadRecordRepo.insert(ctx, doc)
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): GraphQLRequestContext =
        DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
}

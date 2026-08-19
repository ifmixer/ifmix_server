package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.PresignDownloadResult
import com.ifmix.api.core.graphql.generated.types.PresignUploadResult
import com.ifmix.api.core.modules.storage.StorageFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import java.time.Duration
import java.util.UUID

@DgsComponent
class StorageFetcher(
    private val storageFacade: StorageFacade,
) {

    @DgsMutation(field = "mutation_storage_presignUpload")
    fun presignUpload(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): PresignUploadResult {
        val ctx = getContext(dfe)
        val category = input["category"] as String
        val contentType = input["contentType"] as String
        val objectKey = "${category}/${UUID.randomUUID()}.png"
        val uploadUrl = storageFacade.presignUpload(
            ctx = ctx,
            objectKey = objectKey,
            contentType = contentType,
            duration = Duration.ofMinutes(5),
            category = category,
        )
        val downloadUrl = when {
            category == "scan" -> uploadUrl
            else -> storageFacade.presignDownload(objectKey, Duration.ofHours(1))
        }
        return PresignUploadResult(
            mediaId = objectKey,
            uploadUrl = uploadUrl,
            imageKey = objectKey,
            downloadUrl = downloadUrl,
        )
    }

    @DgsMutation(field = "mutation_storage_presignDownload")
    fun presignDownload(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): PresignDownloadResult {
        val imageKey = input["imageKey"] as String
        val durationSeconds = input["durationSeconds"] as? Int ?: 3600
        val downloadUrl = storageFacade.presignDownload(
            objectKey = imageKey,
            duration = Duration.ofSeconds(durationSeconds.toLong()),
        )
        return PresignDownloadResult(downloadUrl = downloadUrl)
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): RequestContext =
        DgsContext.getCustomContext<RequestContext>(dfe)
}

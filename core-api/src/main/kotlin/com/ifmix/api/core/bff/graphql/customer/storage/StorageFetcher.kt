package com.ifmix.api.core.bff.graphql.customer.storage

import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignDownloadPayload
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.generated.types.PresignUploadPayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.storage.service.StorageModuleService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument

@DgsComponent
class StorageFetcher(
    private val storageService: StorageModuleService,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "mutation_storage_presignUpload")
    fun presignUpload(dfe: DgsDataFetchingEnvironment, @InputArgument input: PresignUploadInput): PresignUploadPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val result = storageService.presignUpload(ctx, input)
        return PresignUploadPayload(
            mediaId = result.mediaId,
            uploadUrl = result.uploadUrl,
            imageKey = result.imageKey,
            downloadUrl = result.downloadUrl,
        )
    }

    @DgsMutation(field = "mutation_storage_presignDownload")
    fun presignDownload(dfe: DgsDataFetchingEnvironment, @InputArgument input: PresignDownloadInput): PresignDownloadPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val result = storageService.presignDownload(ctx, input)
        return PresignDownloadPayload(url = result.url)
    }
}

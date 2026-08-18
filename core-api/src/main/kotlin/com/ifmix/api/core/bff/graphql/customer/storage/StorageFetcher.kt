package com.ifmix.api.core.bff.graphql.customer.storage

import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignDownloadPayload
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.generated.types.PresignUploadPayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.storage.service.StorageFacadeService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument

@DgsComponent
class StorageFetcher(
    private val storageService: StorageFacadeService,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "mutation_storage_presignUpload")
    fun presignUpload(dfe: DgsDataFetchingEnvironment, @InputArgument input: PresignUploadInput): PresignUploadPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return storageService.presignUpload(ctx, input)
    }

    @DgsMutation(field = "mutation_storage_presignDownload")
    fun presignDownload(dfe: DgsDataFetchingEnvironment, @InputArgument input: PresignDownloadInput): PresignDownloadPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return storageService.presignDownload(ctx, input)
    }
}

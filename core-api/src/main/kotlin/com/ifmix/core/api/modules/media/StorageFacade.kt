package com.ifmix.core.api.modules.media

import com.ifmix.core.api.dto.storage.PresignDownloadResult
import com.ifmix.core.api.dto.storage.PresignUploadResult
import com.ifmix.core.api.generated.types.PresignDownloadInput
import com.ifmix.core.api.generated.types.PresignUploadInput
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.media.handler.StorageAggHandler
import org.springframework.stereotype.Service

@Service
class StorageFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: StorageAggHandler,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadResult =
        handler.presignUpload(mcFactory.forProject(opCtx), input)

    fun presignDownload(opCtx: OperationContext, input: PresignDownloadInput): PresignDownloadResult =
        handler.presignDownload(mcFactory.forProject(opCtx), input)
}

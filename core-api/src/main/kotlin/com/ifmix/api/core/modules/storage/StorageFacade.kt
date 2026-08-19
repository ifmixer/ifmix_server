package com.ifmix.api.core.modules.storage

import com.ifmix.api.core.dto.storage.PresignDownloadResult
import com.ifmix.api.core.dto.storage.PresignUploadResult
import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.storage.handler.StorageHandler
import org.springframework.stereotype.Service

@Service
class StorageFacade(
    private val svcCtxFactory: SvcCtxFactory,
    private val handler: StorageHandler,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadResult =
        handler.presignUpload(svcCtxFactory.forApp(opCtx), input)

    fun presignDownload(opCtx: OperationContext, input: PresignDownloadInput): PresignDownloadResult =
        handler.presignDownload(svcCtxFactory.forApp(opCtx), input)
}

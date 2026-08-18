package com.ifmix.api.core.modules.storage.service

import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignDownloadPayload
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.generated.types.PresignUploadPayload
import com.ifmix.api.core.infra.http.OperationContext
import org.springframework.stereotype.Service

@Service
class StorageFacadeService(
    private val commands: StorageCommands,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadPayload =
        commands.presignUpload(opCtx, input)

    fun presignDownload(opCtx: OperationContext, input: PresignDownloadInput): PresignDownloadPayload =
        commands.presignDownload(opCtx, input)
}

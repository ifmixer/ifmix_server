package com.ifmix.api.core.modules.storage.service

import com.ifmix.api.core.dto.storage.PresignDownloadResult
import com.ifmix.api.core.dto.storage.PresignUploadResult
import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.infra.http.OperationContext
import org.springframework.stereotype.Service

@Service
class StorageFacadeService(
    private val commands: StorageCommands,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadResult =
        commands.presignUpload(opCtx, input)

    fun presignDownload(opCtx: OperationContext, input: PresignDownloadInput): PresignDownloadResult =
        commands.presignDownload(opCtx, input)
}

package com.ifmix.api.core.modules.storage.service

import com.ifmix.api.core.dto.storage.PresignDownloadResult
import com.ifmix.api.core.dto.storage.PresignUploadResult
import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.storage.service.internal.StorageEntityService
import org.springframework.stereotype.Service

@Service
class StorageModuleService(
    private val svcCtxFactory: SvcCtxFactory,
    private val entityService: StorageEntityService,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadResult =
        entityService.presignUpload(svcCtxFactory.forApp(opCtx), input)

    fun presignDownload(opCtx: OperationContext, input: PresignDownloadInput): PresignDownloadResult =
        entityService.presignDownload(svcCtxFactory.forApp(opCtx), input)
}

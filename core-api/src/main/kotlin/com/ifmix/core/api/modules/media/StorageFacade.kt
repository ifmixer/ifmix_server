package com.ifmix.core.api.modules.media

import com.ifmix.core.api.dto.storage.PresignDownloadResult
import com.ifmix.core.api.dto.storage.PresignUploadResult
import com.ifmix.core.api.dto.storage.PresignDownloadInput
import com.ifmix.core.api.dto.storage.PresignUploadInput
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.media.handler.StorageAggHandler
import org.springframework.stereotype.Service

@Service
class StorageFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: StorageAggHandler,
) {
    fun presignUpload(actionCtx: ActionContext, input: PresignUploadInput): PresignUploadResult =
        handler.presignUpload(mcFactory.forProject(actionCtx), input)

    fun presignDownload(actionCtx: ActionContext, input: PresignDownloadInput): PresignDownloadResult =
        handler.presignDownload(mcFactory.forProject(actionCtx), input)
}

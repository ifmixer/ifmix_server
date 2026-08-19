package com.ifmix.api.core.modules.storage

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.modules.storage.entity.UploadRecordEntity
import com.ifmix.api.core.modules.storage.handler.UploadRecordEntityHandler
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Storage 模块门面：统一暴露预签名 URL 生成与上传记录能力。
 *
 * 职责：
 * - presignUpload / presignDownload：委托 ObjectStorage
 * - recordUpload：委托 UploadRecordEntityHandler（构造实体 + 写入 DB）
 */
@Component
class StorageFacade(
    private val objectStorage: ObjectStorage,
    private val uploadRecordHandler: UploadRecordEntityHandler,
) {

    /**
     * 生成预签名上传 URL，同时记录上传行为。
     *
     * @return 上传 URL（客户端直接 PUT 的地址）
     */
    fun presignUpload(
        ctx: RequestContext,
        objectKey: String,
        contentType: String,
        duration: Duration = Duration.ofMinutes(5),
        category: String? = null,
    ): String {
        val uploadUrl = objectStorage.presignUpload(objectKey, contentType, duration)
        uploadRecordHandler.recordUpload(ctx, objectKey, contentType, category)
        return uploadUrl
    }

    /**
     * 生成预签名下载 URL。
     *
     * @return 下载 URL（含签名，可直接用于 GET）
     */
    fun presignDownload(
        objectKey: String,
        duration: Duration = Duration.ofHours(1),
    ): String = objectStorage.presignDownload(objectKey, duration)
}

package com.ifmix.api.core.modules.storage.handler

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.storage.entity.UploadRecordEntity
import com.ifmix.api.core.modules.storage.repo.UploadRecordRepo
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 上传记录实体处理器。
 *
 * 负责构造 UploadRecordEntity 并调用仓储写入。
 */
@Component
class UploadRecordEntityHandler(
    private val uploadRecordRepo: UploadRecordRepo,
) {

    /**
     * 记录一次上传行为。
     *
     * @param ctx      请求上下文（含 appId、installId、userId）
     * @param objectKey 对象键
     * @param contentType 内容类型
     * @param category  上传类别（如 "scan"、"avatar"），可为 null
     */
    fun recordUpload(
        ctx: RequestContext,
        objectKey: String,
        contentType: String,
        category: String? = null,
    ) {
        try {
            val doc = UploadRecordEntity().apply {
                this.objectKey = objectKey
                this.contentType = contentType
                this.category = category
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
            }
            uploadRecordRepo.insert(ctx, doc)
        } catch (_: Exception) {
            // best-effort：记录失败不影响主流程
        }
    }
}

package com.ifmix.api.core.modules.storage.entity

import com.ifmix.api.core.common.db.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * 上传记录文档。
 *
 * 追踪每次通过 presign upload 上传的对象，用于审计和用量统计。
 */
@Document(collection = "core_upload_record")
class UploadRecordEntity : BaseEntity() {

    var appId: String = ""

    var installId: String? = null

    var userId: String? = null

    var objectKey: String? = null

    var contentType: String? = null

    /** 上传类别，如 "scan"、"avatar" 等。 */
    var category: String? = null

    var clientIp: String? = null
}

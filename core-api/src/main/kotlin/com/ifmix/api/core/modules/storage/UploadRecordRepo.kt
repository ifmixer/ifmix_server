package com.ifmix.api.core.modules.storage

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

/**
 * 上传记录仓储。
 *
 * 提供 insert 方法，用于记录每次 presign upload 行为。
 */
@Component
class UploadRecordRepo(private val mongo: MongoTemplate) {

    /**
     * 插入一条上传记录。
     *
     * @param ctx     请求上下文（含 appId、installId、userId）
     * @param doc     要插入的 UploadRecordDocument（字段已由调用方填充）
     */
    fun insert(ctx: RequestContext, doc: UploadRecordDocument) {
        doc.appId = ctx.appId
        if (doc.installId == null) doc.installId = ctx.installId
        if (doc.userId == null) doc.userId = ctx.userId
        mongo.insert(doc)
    }
}

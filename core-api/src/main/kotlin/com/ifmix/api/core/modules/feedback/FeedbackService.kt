package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import com.ifmix.api.core.common.service.CRUDService

/**
 * feedback 业务逻辑：**组合**持有通用 CRUDService（不继承），委托通用 CRUD，只实现定制逻辑。
 */
class FeedbackService(val crud: CRUDService<FeedbackDocument>) {

    /** 提交反馈，返回新建文档的 id。 */
    fun submit(ctx: RequestContext, req: SubmitReq): String {
        val doc = FeedbackDocument().apply {
            appId = ObjectId(ctx.appId)
            installId = ctx.installId
            userId = ctx.userId
            scanRecordId = req.scanRecordId
            category = req.category
            note = req.note
        }
        return crud.createOne(ctx, doc)
    }
}

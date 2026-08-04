package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.bff.customer.SubmitFeedbackReq
import com.ifmix.api.core.bff.customer.SubmitFeedbackRes
import com.ifmix.api.core.entity.feedback.Feedback
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.http.mustGetInstallId
import com.ifmix.api.core.modules.feedback.repo.FeedbackRepository
import com.ifmix.api.core.modules.base.BaseAppCrudService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Feedback 业务逻辑。继承自 BaseAppCrudService，获得基本 CRUD 操作。
 * 领域特有方法（如 submit）在此追加。
 */
@Service
class FeedbackService(
    private val feedbackRepo: FeedbackRepository,
) : BaseAppCrudService<Feedback>(feedbackRepo) {

    /** 提交反馈，返回新建记录的 ID。身份从 ctx 推导。 */
    @Transactional
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): SubmitFeedbackRes {
        val appId = ctx.mustGetAppId()
        val installId = ctx.mustGetInstallId()
        val userId = ctx.userId

        val saved = feedbackRepo.create(
            ctx = ctx,
            appId = appId,
            installId = installId,
            userId = userId,
            category = req.category,
            comment = req.comment,
            scanRecordId = req.scanRecordId,
        )
        return SubmitFeedbackRes(id = saved.id)
    }
}

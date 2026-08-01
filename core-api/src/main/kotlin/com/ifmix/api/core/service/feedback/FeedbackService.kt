package com.ifmix.api.core.service.feedback

import com.ifmix.api.core.bff.customer.SubmitFeedbackReq
import com.ifmix.api.core.bff.customer.SubmitFeedbackRes
import com.ifmix.api.core.entity.feedback.Feedback
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.repository.feedback.FeedbackRepository
import com.ifmix.api.core.service.base.BaseAppCrudService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
        val appId = runCatching { UUID.fromString(ctx.appId) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id must be a UUID")
        val installId = ctx.installId
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-install-id must be a UUID")
        val userId = ctx.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val saved = feedbackRepo.create(
            ctx = ctx,
            appId = appId,
            installId = installId,
            userId = userId,
            category = req.category,
            comment = req.comment,
            scanRecordId = req.scanRecordId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        )
        return SubmitFeedbackRes(id = saved.id.toString())
    }
}

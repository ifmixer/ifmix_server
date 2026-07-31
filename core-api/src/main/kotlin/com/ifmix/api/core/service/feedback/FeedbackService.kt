package com.ifmix.api.core.service.feedback

import com.ifmix.api.core.entity.feedback.Feedback
import com.ifmix.api.core.entity.feedback.dto.FeedbackCreateInput
import com.ifmix.api.core.entity.feedback.dto.FeedbackView
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
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

    /** 提交反馈，返回新建记录的视图。 */
    @Transactional
    fun submit(ctx: RequestContext, input: FeedbackCreateInput): FeedbackView {
        val appId = runCatching { UUID.fromString(ctx.appId) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id must be a UUID")
        return FeedbackView(feedbackRepo.create(appId, input))
    }
}

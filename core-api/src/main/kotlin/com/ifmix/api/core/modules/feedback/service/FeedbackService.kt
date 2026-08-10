package com.ifmix.api.core.modules.feedback.service

import com.ifmix.api.core.entity.feedback.Feedback
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.http.mustGetInstallId
import com.ifmix.api.core.infra.service.BaseAppCrudService
import com.ifmix.api.core.infra.dto.CreateOneRes
import com.ifmix.api.core.modules.feedback.dto.SubmitFeedbackReq
import com.ifmix.api.core.modules.feedback.repo.FeedbackRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FeedbackService(
    private val feedbackRepo: FeedbackRepository,
) : BaseAppCrudService<Feedback>(feedbackRepo) {

    @Transactional
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): CreateOneRes {
        val appId = ctx.mustGetAppId()
        val installId = ctx.mustGetInstallId()
        val userId = ctx.userId

        val id = feedbackRepo.create(
            ctx = ctx.repoCtx,
            appId = appId,
            installId = installId,
            userId = userId,
            category = req.category,
            comment = req.comment,
            scanRecordId = req.scanRecordId,
        )
        return CreateOneRes(id = id)
    }
}

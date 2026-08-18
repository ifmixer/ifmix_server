package com.ifmix.api.core.modules.feedback.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.dto.common.CreateOneRes
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.model.feedback.Feedback
import com.ifmix.api.core.dto.feedback.SubmitFeedbackReq
import com.ifmix.api.core.modules.feedback.repo.FeedbackRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class FeedbackFacadeService(
    private val feedbackRepo: FeedbackRepository,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    @Transactional
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): CreateOneRes {
        val appId = ctx.appId!!
        val installId = ctx.installId!!
        val userId = ctx.userId
        val id = UuidV7.generate()
        val feedback = Feedback(id = id, appId = appId, installId = installId, userId = userId,
            category = req.category.toShort(), comment = req.comment,
            scanRecordId = req.scanRecordId, createdAt = Instant.now())
        feedbackRepo.insert(svc(ctx), feedback)
        return CreateOneRes(id = id)
    }
}

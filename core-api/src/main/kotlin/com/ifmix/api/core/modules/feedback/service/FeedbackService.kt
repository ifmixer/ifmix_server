package com.ifmix.api.core.modules.feedback.service

import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.CreateOneRes
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.http.mustGetInstallId
import com.ifmix.api.core.model.Feedback
import com.ifmix.api.core.modules.feedback.dto.SubmitFeedbackReq
import com.ifmix.api.core.modules.feedback.repo.FeedbackRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class FeedbackService(
    private val feedbackRepo: FeedbackRepository,
) {

    @Transactional
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): CreateOneRes {
        val appId = ctx.mustGetAppId()
        val installId = ctx.mustGetInstallId()
        val userId = ctx.userId
        val id = UuidV7.generate()

        val feedback = Feedback(
            id = id,
            appId = appId,
            installId = installId,
            userId = userId,
            category = req.category.toShort(),
            comment = req.comment,
            scanRecordId = req.scanRecordId,
            createdAt = Instant.now(),
        )
        feedbackRepo.insert(ctx.repoCtx, feedback)
        return CreateOneRes(id = id)
    }
}

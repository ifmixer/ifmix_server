package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.http.mustGetInstallId
import com.ifmix.api.core.infra.dto.CreateOneRes
import com.ifmix.api.core.modules.feedback.dto.SubmitFeedbackReq
import com.ifmix.api.core.modules.feedback.repo.FeedbackRepository
import org.springframework.stereotype.Service

@Service
class FeedbackFacade(
    private val feedbackRepo: FeedbackRepository,
) {

    /** 写操作 — 有事务 */
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

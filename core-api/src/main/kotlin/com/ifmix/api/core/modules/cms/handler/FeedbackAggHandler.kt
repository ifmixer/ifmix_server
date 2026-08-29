package com.ifmix.api.core.modules.cms.handler

import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.dto.cms.SubmitFeedbackReq
import com.ifmix.api.core.entity.cms.Feedback
import com.ifmix.api.core.modules.cms.repo.FeedbackRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class FeedbackAggHandler(
    private val feedbackRepo: FeedbackRepository,
) {
    fun submit(mc: ModuleCtx, req: SubmitFeedbackReq): UUID {
        val id = UuidV7.generate()
        val feedback = Feedback {
            this.id = id
            this.appId = mc.op.appId!!
            this.installId = mc.op.installId!!
            this.userId = mc.op.userId
            this.category = req.category
            this.comment = req.comment
            this.scanRecordId = req.scanRecordId
            this.spm = req.spm
            this.createdAt = Instant.now()
        }
        feedbackRepo.save(mc, feedback)
        return id
    }
}

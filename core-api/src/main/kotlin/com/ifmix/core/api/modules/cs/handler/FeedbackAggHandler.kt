package com.ifmix.core.api.modules.cs.handler

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.dto.cs.SubmitFeedbackReq
import com.ifmix.core.api.entity.cs.Feedback
import com.ifmix.core.api.modules.cs.repo.FeedbackRepository
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
            this.projectId = mc.op.projectId!!
            this.customerId = mc.op.actorId
            this.installId = mc.op.installId
            this.topic = req.topic
            this.reasons = req.reasons.toTypedArray()
            this.email = req.email
            this.phone = req.phone
            this.comment = req.comment
            this.scanRecordId = req.scanRecordId
            this.spm = req.spm
            this.createdAt = Instant.now()
        }
        feedbackRepo.save(mc, feedback)
        return id
    }
}

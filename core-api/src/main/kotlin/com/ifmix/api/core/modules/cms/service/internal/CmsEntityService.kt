package com.ifmix.api.core.modules.cms.service.internal

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.dto.cms.SubmitFeedbackReq
import com.ifmix.api.core.entity.cms.Feedback
import com.ifmix.api.core.modules.cms.repo.FeedbackRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class FeedbackEntityService(
    private val feedbackRepo: FeedbackRepository,
) {
    fun submit(sc: SvcCtx, req: SubmitFeedbackReq): UUID {
        val id = UuidV7.generate()
        val feedback = Feedback(id = id, appId = sc.op.appId!!, installId = sc.op.installId!!, userId = sc.op.userId,
            category = req.category.toShort(), comment = req.comment,
            scanRecordId = req.scanRecordId, createdAt = Instant.now())
        feedbackRepo.save(sc, feedback)
        return id
    }
}

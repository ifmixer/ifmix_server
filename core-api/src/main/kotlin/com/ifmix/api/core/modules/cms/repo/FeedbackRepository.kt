package com.ifmix.api.core.modules.cms.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreFeedback.Companion.CORE_FEEDBACK
import com.ifmix.api.core.entity.cms.Feedback
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Feedback repository.
 */
@Repository
class FeedbackRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_FEEDBACK,
        idField = CORE_FEEDBACK.ID,
        appIdField = CORE_FEEDBACK.APP_ID,
        type = Feedback::class.java,
    )

    fun insert(ctx: SvcCtx, feedback: Feedback) = crud.insert(ctx, feedback)
    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Feedback? = crud.findById(ctx, appId, id)
}

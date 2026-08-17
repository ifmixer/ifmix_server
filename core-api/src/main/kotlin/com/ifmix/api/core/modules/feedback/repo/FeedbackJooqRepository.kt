package com.ifmix.api.core.modules.feedback.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreFeedback.Companion.CORE_FEEDBACK
import com.ifmix.api.core.model.Feedback
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Feedback jOOQ repository.
 * The original Jimmer FeedbackRepository is preserved for Wave 5 cleanup.
 */
@Repository
class FeedbackJooqRepository(private val crud: CrudOps) {

    fun insert(ctx: RepoContext, feedback: Feedback) =
        crud.insert(ctx, CORE_FEEDBACK, feedback)

    fun findById(ctx: RepoContext, appId: UUID, id: UUID): Feedback? =
        crud.findById(ctx, CORE_FEEDBACK, CORE_FEEDBACK.APP_ID, CORE_FEEDBACK.ID, appId, id, Feedback::class.java)
}

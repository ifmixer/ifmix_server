package com.ifmix.api.core.modules.cms.repo

import com.ifmix.api.core.entity.cms.Feedback
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FeedbackRepository {
    companion object { private val tpl = AppCrudRepoTemplate(Feedback::class) }

    fun save(mc: ModuleCtx, entity: Feedback) = tpl.save(mc, entity)
}

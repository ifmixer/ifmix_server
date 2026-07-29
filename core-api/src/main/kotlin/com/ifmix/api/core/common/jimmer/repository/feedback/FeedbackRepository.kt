package com.ifmix.api.core.common.jimmer.repository.feedback

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.feedback.Feedback
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

@Component
class FeedbackRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Feedback>(sql, Feedback::class)

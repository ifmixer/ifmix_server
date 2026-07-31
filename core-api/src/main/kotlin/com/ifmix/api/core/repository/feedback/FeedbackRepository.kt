package com.ifmix.api.core.repository.feedback

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.feedback.Feedback
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository

@Repository
class FeedbackRepository(sql: KSqlClient,) : BaseAppCrudRepository<Feedback>(sql, Feedback::class)

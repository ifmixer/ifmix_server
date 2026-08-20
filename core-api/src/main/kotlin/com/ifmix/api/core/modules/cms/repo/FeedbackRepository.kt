package com.ifmix.api.core.modules.cms.repo

import com.ifmix.api.core.entity.cms.Feedback
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository

@Repository
class FeedbackRepository(sql: KSqlClient) : BaseAppCrudRepository<Feedback>(sql, Feedback::class)

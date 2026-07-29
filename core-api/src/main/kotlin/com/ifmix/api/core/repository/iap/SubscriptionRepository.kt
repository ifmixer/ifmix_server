package com.ifmix.api.core.repository.iap

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.iap.Subscription
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Subscription repository (basic CRUD only) */
@Component
class SubscriptionRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Subscription>(sql, Subscription::class)

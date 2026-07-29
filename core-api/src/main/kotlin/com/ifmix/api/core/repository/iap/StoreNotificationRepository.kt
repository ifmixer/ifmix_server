package com.ifmix.api.core.repository.iap

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.iap.StoreNotification
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** StoreNotification repository (basic CRUD only) */
@Component
class StoreNotificationRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<StoreNotification>(sql, StoreNotification::class)

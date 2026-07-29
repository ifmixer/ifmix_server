package com.ifmix.api.core.repository.iap

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.iap.StoreNotification
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.util.UUID

/** StoreNotification repository with idempotency check method */
@Component
class StoreNotificationRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<StoreNotification>(sql, StoreNotification::class) {

    /**
     * Check if a notification has already been processed by platform and purchase token.
     * Used for idempotency in notification handling.
     */
    fun existsByPlatformAndToken(platform: String, purchaseToken: String): Boolean {
        return findAll().any {
            it.platform == platform &&
            it.purchaseToken == purchaseToken &&
            it.processed &&
            it.deletedAt == null
        }
    }
}

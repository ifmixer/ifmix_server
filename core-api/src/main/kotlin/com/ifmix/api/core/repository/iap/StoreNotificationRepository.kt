package com.ifmix.api.core.repository.iap

import com.ifmix.api.core.entity.iap.StoreNotification
import com.ifmix.api.core.entity.iap.platform
import com.ifmix.api.core.entity.iap.processed
import com.ifmix.api.core.entity.iap.purchaseToken
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Component

/** StoreNotification repository with idempotency check method */
@Component
class StoreNotificationRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<StoreNotification>(sql, StoreNotification::class) {

    /**
     * Check if a notification has already been processed by platform and purchase token.
     * Used for idempotency in notification handling.
     * @LogicalDeleted auto-filters deleted records.
     */
    fun existsByPlatformAndToken(platform: String, purchaseToken: String): Boolean {
        val results = sql.createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute()
        return results.isNotEmpty()
    }
}

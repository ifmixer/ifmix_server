package com.ifmix.api.core.modules.iap.repo

import com.ifmix.api.core.entity.iap.StoreNotification
import com.ifmix.api.core.entity.iap.platform
import com.ifmix.api.core.entity.iap.processed
import com.ifmix.api.core.entity.iap.purchaseToken
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository

/** StoreNotification repository with idempotency check method */
@Repository
class StoreNotificationRepository(sql: KSqlClient,) : BaseAppCrudRepository<StoreNotification>(sql, StoreNotification::class) {

    /**
     * Check if a notification has already been processed by platform and purchase token.
     * Used for idempotency in notification handling.
     * @LogicalDeleted auto-filters deleted records.
     */
    fun existsByPlatformAndToken(ctx: OperationContext, platform: String, purchaseToken: String): Boolean {
        val results = sql.createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute()
        return results.isNotEmpty()
    }
}

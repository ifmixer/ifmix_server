package com.ifmix.api.core.modules.iap.repo

import com.ifmix.api.core.entity.iap.StoreNotification
import com.ifmix.api.core.entity.iap.platform
import com.ifmix.api.core.entity.iap.processed
import com.ifmix.api.core.entity.iap.purchaseToken
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository

@Repository
class StoreNotificationRepository(sql: KSqlClient,) : BaseAppCrudRepository<StoreNotification>(sql, StoreNotification::class) {

    fun existsByPlatformAndToken(ctx: RepoContext, platform: String, purchaseToken: String): Boolean {
        val results = sql.createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute()
        return results.isNotEmpty()
    }
}

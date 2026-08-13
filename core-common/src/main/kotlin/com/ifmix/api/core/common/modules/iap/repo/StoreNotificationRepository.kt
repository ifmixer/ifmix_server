package com.ifmix.api.core.common.modules.iap.repo

import com.ifmix.api.core.common.entity.iap.StoreNotification
import com.ifmix.api.core.common.entity.iap.platform
import com.ifmix.api.core.common.entity.iap.processed
import com.ifmix.api.core.common.entity.iap.purchaseToken
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository

@Repository
class StoreNotificationRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<StoreNotification>(clusterRegistry, StoreNotification::class) {

    fun existsByPlatformAndToken(ctx: RepoContext, platform: String, purchaseToken: String): Boolean {
        val results = sql(ctx).createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute()
        return results.isNotEmpty()
    }
}

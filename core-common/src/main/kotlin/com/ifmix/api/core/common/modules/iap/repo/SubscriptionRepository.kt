package com.ifmix.api.core.common.modules.iap.repo

import com.ifmix.api.core.common.entity.iap.Subscription
import com.ifmix.api.core.common.entity.iap.active
import com.ifmix.api.core.common.entity.iap.appId
import com.ifmix.api.core.common.entity.iap.originalTransactionId
import com.ifmix.api.core.common.entity.iap.subscriptionPxid
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SubscriptionRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<Subscription>(clusterRegistry, Subscription::class) {

    fun upsertSubscription(ctx: RepoContext, entity: Subscription): UUID {
        return save(ctx, entity).id
    }

    fun findActiveByPxid(ctx: RepoContext, appId: UUID, pxid: String): Subscription? {
        return sql(ctx).createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            where(table.active eq true)
            select(table)
        }.fetchOneOrNull()
    }

    fun findByPxid(ctx: RepoContext, appId: UUID, pxid: String): Subscription? {
        return sql(ctx).createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            select(table)
        }.fetchOneOrNull()
    }

    fun updateByOriginalTxn(ctx: RepoContext, appId: UUID, originalTxnId: String, updater: (Subscription) -> Subscription): Boolean {
        val existing = sql(ctx).createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.originalTransactionId eq originalTxnId)
            select(table)
        }.fetchOneOrNull() ?: return false
        val updated = updater(existing)
        save(ctx, updated)
        return true
    }
}

package com.ifmix.api.core.modules.iap.repo

import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.entity.iap.active
import com.ifmix.api.core.entity.iap.appId
import com.ifmix.api.core.entity.iap.originalTransactionId
import com.ifmix.api.core.entity.iap.subscriptionPxid
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SubscriptionRepository(sql: KSqlClient,) : BaseAppCrudRepository<Subscription>(sql, Subscription::class) {

    fun upsertSubscription(ctx: RepoContext, entity: Subscription): UUID {
        return save(ctx, entity).id
    }

    fun findActiveByPxid(ctx: RepoContext, appId: UUID, pxid: String): Subscription? {
        return sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            where(table.active eq true)
            select(table)
        }.fetchOneOrNull()
    }

    fun findByPxid(ctx: RepoContext, appId: UUID, pxid: String): Subscription? {
        return sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            select(table)
        }.fetchOneOrNull()
    }

    fun updateByOriginalTxn(ctx: RepoContext, appId: UUID, originalTxnId: String, updater: (Subscription) -> Subscription): Boolean {
        val existing = sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.originalTransactionId eq originalTxnId)
            select(table)
        }.fetchOneOrNull() ?: return false
        val updated = updater(existing)
        save(ctx, updated)
        return true
    }
}

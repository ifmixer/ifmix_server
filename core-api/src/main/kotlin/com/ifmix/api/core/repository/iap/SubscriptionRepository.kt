package com.ifmix.api.core.repository.iap

import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.entity.iap.active
import com.ifmix.api.core.entity.iap.appId
import com.ifmix.api.core.entity.iap.originalTransactionId
import com.ifmix.api.core.entity.iap.subscriptionPxid
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

/** Subscription repository with custom query methods */
@Repository
class SubscriptionRepository(sql: KSqlClient,) : BaseAppCrudRepository<Subscription>(sql, Subscription::class) {

    /**
     * Upsert subscription based on subscriptionPxid key.
     * Uses Jimmer's save which upserts when @Key is defined.
     */
    fun upsertSubscription(repo: RepoContext, entity: Subscription): Subscription {
        return save(repo, entity)
    }

    /**
     * Find active subscription by appId and subscriptionPxid.
     * @LogicalDeleted auto-filters deleted records.
     */
    fun findActiveByPxid(repo: RepoContext, appId: UUID, pxid: String): Subscription? {
        return sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            where(table.active eq true)
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Find any non-deleted subscription by pxid (regardless of active status).
     */
    fun findByPxid(repo: RepoContext, appId: UUID, pxid: String): Subscription? {
        return sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Find subscription by originalTransactionId and update it.
     * @LogicalDeleted auto-filters deleted records.
     */
    fun updateByOriginalTxn(repo: RepoContext, appId: UUID, originalTxnId: String, updater: (Subscription) -> Subscription): Subscription? {
        val existing = sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.originalTransactionId eq originalTxnId)
            select(table)
        }.fetchOneOrNull() ?: return null
        val updated = updater(existing)
        return save(repo, updated)
    }
}

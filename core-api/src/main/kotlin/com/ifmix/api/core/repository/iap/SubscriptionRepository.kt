package com.ifmix.api.core.repository.iap

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.iap.Subscription
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Subscription repository with custom query methods */
@Component
class SubscriptionRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Subscription>(sql, Subscription::class) {

    /**
     * Upsert subscription based on subscriptionPxid key.
     * Uses Jimmer's save which upserts when @Key is defined.
     */
    fun upsertSubscription(entity: Subscription): Subscription {
        return save(entity)
    }

    /**
     * Find active subscription by appId and subscriptionPxid.
     */
    fun findActiveByPxid(appId: UUID, pxid: String?): Subscription? {
        val all = findAll()
        return all.firstOrNull {
            it.appId == appId &&
            it.subscriptionPxid == pxid &&
            it.active &&
            it.deletedAt == null
        }
    }

    /**
     * Find subscription by originalTransactionId and update it.
     */
    fun updateByOriginalTxn(appId: UUID, originalTxnId: String, updater: (Subscription) -> Subscription): Subscription? {
        val all = findAll()
        val existing = all.firstOrNull {
            it.appId == appId &&
            it.originalTransactionId == originalTxnId &&
            it.deletedAt == null
        } ?: return null
        val updated = updater(existing)
        return save(updated)
    }
}

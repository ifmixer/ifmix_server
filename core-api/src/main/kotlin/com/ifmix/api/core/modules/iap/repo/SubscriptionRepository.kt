package com.ifmix.api.core.modules.iap.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.jooq.tables.CoreSubscription.Companion.CORE_SUBSCRIPTION
import com.ifmix.api.core.model.iap.Subscription
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Subscription repository.
 */
@Repository
class SubscriptionRepository {

    fun upsertSubscription(ctx: RepoContext, subscription: Subscription): UUID {
        val rawJsonb = subscription.rawResponse?.let { JSONB.jsonb(it) }
        val record = ctx.dsl.newRecord(CORE_SUBSCRIPTION, subscription)
        // newRecord from plain data class may not set rawResponse correctly — overwrite
        record.rawResponse = rawJsonb
        ctx.dsl.executeInsert(record)
        return subscription.id
    }

    fun findActiveByPxid(ctx: RepoContext, appId: UUID, pxid: String): Subscription? =
        ctx.dsl.selectFrom(CORE_SUBSCRIPTION)
            .where(CORE_SUBSCRIPTION.APP_ID.eq(appId))
            .and(CORE_SUBSCRIPTION.SUBSCRIPTION_PXID.eq(pxid))
            .and(CORE_SUBSCRIPTION.ACTIVE.eq(true))
            .and(CORE_SUBSCRIPTION.DELETED_AT.isNull)
            .fetchOne()?.let { mapToModel(it) }

    fun findByPxid(ctx: RepoContext, appId: UUID, pxid: String): Subscription? =
        ctx.dsl.selectFrom(CORE_SUBSCRIPTION)
            .where(CORE_SUBSCRIPTION.APP_ID.eq(appId))
            .and(CORE_SUBSCRIPTION.SUBSCRIPTION_PXID.eq(pxid))
            .and(CORE_SUBSCRIPTION.DELETED_AT.isNull)
            .fetchOne()?.let { mapToModel(it) }

    fun updateByOriginalTxn(
        ctx: RepoContext,
        appId: UUID,
        originalTxnId: String,
        block: (Subscription) -> Subscription,
    ): Boolean {
        val existing = findByOriginalTxn(ctx, appId, originalTxnId) ?: return false
        val updated = block(existing)
        val rawJsonb = updated.rawResponse?.let { JSONB.jsonb(it) }
        ctx.dsl.update(CORE_SUBSCRIPTION)
            .set(CORE_SUBSCRIPTION.ID, updated.id)
            .set(CORE_SUBSCRIPTION.APP_ID, updated.appId)
            .set(CORE_SUBSCRIPTION.SUBSCRIPTION_PXID, updated.subscriptionPxid)
            .set(CORE_SUBSCRIPTION.ORIGINAL_TRANSACTION_ID, updated.originalTransactionId)
            .set(CORE_SUBSCRIPTION.PRODUCT_ID, updated.productId)
            .set(CORE_SUBSCRIPTION.PLATFORM, updated.platform)
            .set(CORE_SUBSCRIPTION.ACTIVE, updated.active)
            .set(CORE_SUBSCRIPTION.SUB_STATUS, updated.subStatus)
            .set(CORE_SUBSCRIPTION.EXPIRY_DATE, updated.expiryDate)
            .set(CORE_SUBSCRIPTION.PURCHASE_TOKEN, updated.purchaseToken)
            .set(CORE_SUBSCRIPTION.RAW_RESPONSE, rawJsonb)
            .set(CORE_SUBSCRIPTION.UPDATED_AT, java.time.Instant.now())
            .where(CORE_SUBSCRIPTION.APP_ID.eq(appId))
            .and(CORE_SUBSCRIPTION.ORIGINAL_TRANSACTION_ID.eq(originalTxnId))
            .execute()
        return true
    }

    private fun findByOriginalTxn(ctx: RepoContext, appId: UUID, originalTxnId: String): Subscription? =
        ctx.dsl.selectFrom(CORE_SUBSCRIPTION)
            .where(CORE_SUBSCRIPTION.APP_ID.eq(appId))
            .and(CORE_SUBSCRIPTION.ORIGINAL_TRANSACTION_ID.eq(originalTxnId))
            .and(CORE_SUBSCRIPTION.DELETED_AT.isNull)
            .fetchOne()?.let { mapToModel(it) }

    private fun mapToModel(record: com.ifmix.api.core.jooq.tables.records.CoreSubscriptionRecord): Subscription {
        val rawStr = record.rawResponse?.toString()
        return Subscription(
            id = record.id!!,
            appId = record.appId!!,
            subscriptionPxid = record.subscriptionPxid ?: "",
            originalTransactionId = record.originalTransactionId,
            productId = record.productId,
            platform = record.platform ?: 0,
            active = record.active ?: false,
            subStatus = record.subStatus,
            expiryDate = record.expiryDate,
            purchaseToken = record.purchaseToken,
            rawResponse = rawStr,
            createdAt = record.createdAt ?: java.time.Instant.now(),
            updatedAt = record.updatedAt,
            deletedAt = record.deletedAt,
        )
    }
}

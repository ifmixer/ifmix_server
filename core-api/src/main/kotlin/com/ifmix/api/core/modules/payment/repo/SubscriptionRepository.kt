package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.jooq.tables.CoreSubscription.Companion.CORE_SUBSCRIPTION
import com.ifmix.api.core.entity.iap.Subscription
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class SubscriptionRepository {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            Subscription::id.name to CORE_SUBSCRIPTION.ID,
            Subscription::appId.name to CORE_SUBSCRIPTION.APP_ID,
            Subscription::subscriptionPxid.name to CORE_SUBSCRIPTION.SUBSCRIPTION_PXID,
            Subscription::originalTransactionId.name to CORE_SUBSCRIPTION.ORIGINAL_TRANSACTION_ID,
            Subscription::productId.name to CORE_SUBSCRIPTION.PRODUCT_ID,
            Subscription::platform.name to CORE_SUBSCRIPTION.PLATFORM,
            Subscription::active.name to CORE_SUBSCRIPTION.ACTIVE,
            Subscription::expiryDate.name to CORE_SUBSCRIPTION.EXPIRY_DATE,
            Subscription::createdAt.name to CORE_SUBSCRIPTION.CREATED_AT,
            Subscription::updatedAt.name to CORE_SUBSCRIPTION.UPDATED_AT,
        )
    }

    fun upsertSubscription(ctx: SvcCtx, subscription: Subscription): UUID {
        val record = ctx.dsl.newRecord(CORE_SUBSCRIPTION, subscription)
        ctx.dsl.executeInsert(record)
        return subscription.id
    }

    fun findActiveByPxid(ctx: SvcCtx, appId: UUID, pxid: String): Subscription? =
        ctx.dsl.selectFrom(CORE_SUBSCRIPTION)
            .where(CORE_SUBSCRIPTION.APP_ID.eq(appId))
            .and(CORE_SUBSCRIPTION.SUBSCRIPTION_PXID.eq(pxid))
            .and(CORE_SUBSCRIPTION.ACTIVE.eq(true))
            .and(CORE_SUBSCRIPTION.DELETED_AT.isNull)
            .fetchOneInto(Subscription::class.java)

    fun findByPxid(ctx: SvcCtx, appId: UUID, pxid: String): Subscription? =
        ctx.dsl.selectFrom(CORE_SUBSCRIPTION)
            .where(CORE_SUBSCRIPTION.APP_ID.eq(appId))
            .and(CORE_SUBSCRIPTION.SUBSCRIPTION_PXID.eq(pxid))
            .and(CORE_SUBSCRIPTION.DELETED_AT.isNull)
            .fetchOneInto(Subscription::class.java)

    fun updateByOriginalTxn(ctx: SvcCtx, appId: UUID, originalTxnId: String, block: (Subscription) -> Subscription): Boolean {
        val existing = findByOriginalTxn(ctx, appId, originalTxnId) ?: return false
        val updated = block(existing)
        val record = ctx.dsl.newRecord(CORE_SUBSCRIPTION, updated)
        record.updatedAt = Instant.now()
        record.changed(CORE_SUBSCRIPTION.ID, false) // don't update PK
        ctx.dsl.executeUpdate(record)
        return true
    }

    private fun findByOriginalTxn(ctx: SvcCtx, appId: UUID, originalTxnId: String): Subscription? =
        ctx.dsl.selectFrom(CORE_SUBSCRIPTION)
            .where(CORE_SUBSCRIPTION.APP_ID.eq(appId))
            .and(CORE_SUBSCRIPTION.ORIGINAL_TRANSACTION_ID.eq(originalTxnId))
            .and(CORE_SUBSCRIPTION.DELETED_AT.isNull)
            .fetchOneInto(Subscription::class.java)
}

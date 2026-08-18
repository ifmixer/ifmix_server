package com.ifmix.api.core.modules.iap.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.jooq.tables.CoreStoreNotification.Companion.CORE_STORE_NOTIFICATION
import com.ifmix.api.core.model.StoreNotification
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * StoreNotification repository.
 */
@Repository
class StoreNotificationRepository {

    fun insert(ctx: RepoContext, notification: StoreNotification) {
        val rawJsonb = notification.rawPayload?.let { JSONB.jsonb(it) }
        ctx.dsl.insertInto(
            CORE_STORE_NOTIFICATION,
            CORE_STORE_NOTIFICATION.ID,
            CORE_STORE_NOTIFICATION.APP_ID,
            CORE_STORE_NOTIFICATION.PLATFORM,
            CORE_STORE_NOTIFICATION.SUBSCRIPTION_PXID,
            CORE_STORE_NOTIFICATION.PURCHASE_TOKEN,
            CORE_STORE_NOTIFICATION.NOTIFICATION_TYPE,
            CORE_STORE_NOTIFICATION.RAW_PAYLOAD,
            CORE_STORE_NOTIFICATION.PROCESSED,
            CORE_STORE_NOTIFICATION.PROCESSED_AT,
            CORE_STORE_NOTIFICATION.CREATED_AT,
            CORE_STORE_NOTIFICATION.UPDATED_AT,
            CORE_STORE_NOTIFICATION.DELETED_AT,
        )
            .values(
                notification.id,
                notification.appId,
                notification.platform,
                notification.subscriptionPxid,
                notification.purchaseToken,
                notification.notificationType,
                rawJsonb,
                notification.processed,
                notification.processedAt,
                notification.createdAt,
                notification.updatedAt,
                notification.deletedAt,
            )
            .execute()
    }

    fun existsByPlatformAndToken(ctx: RepoContext, platform: String, purchaseToken: String): Boolean =
        ctx.dsl.fetchExists(
            CORE_STORE_NOTIFICATION,
            CORE_STORE_NOTIFICATION.PLATFORM.eq(platform)
                .and(CORE_STORE_NOTIFICATION.PURCHASE_TOKEN.eq(purchaseToken))
                .and(CORE_STORE_NOTIFICATION.PROCESSED.eq(true)),
        )
}

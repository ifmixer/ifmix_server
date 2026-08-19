package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreStoreNotification.Companion.CORE_STORE_NOTIFICATION
import com.ifmix.api.core.entity.iap.StoreNotification
import org.jooq.TableField
import org.springframework.stereotype.Repository

@Repository
class StoreNotificationRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            StoreNotification::id.name to CORE_STORE_NOTIFICATION.ID,
            StoreNotification::appId.name to CORE_STORE_NOTIFICATION.APP_ID,
            StoreNotification::platform.name to CORE_STORE_NOTIFICATION.PLATFORM,
            StoreNotification::subscriptionPxid.name to CORE_STORE_NOTIFICATION.SUBSCRIPTION_PXID,
            StoreNotification::notificationType.name to CORE_STORE_NOTIFICATION.NOTIFICATION_TYPE,
            StoreNotification::processed.name to CORE_STORE_NOTIFICATION.PROCESSED,
            StoreNotification::processedAt.name to CORE_STORE_NOTIFICATION.PROCESSED_AT,
            StoreNotification::createdAt.name to CORE_STORE_NOTIFICATION.CREATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_STORE_NOTIFICATION,
        idField = CORE_STORE_NOTIFICATION.ID,
        appIdField = null,
        type = StoreNotification::class.java,
    )

    fun insert(ctx: SvcCtx, notification: StoreNotification) {
        val record = ctx.dsl.newRecord(CORE_STORE_NOTIFICATION, notification)
        ctx.dsl.executeInsert(record)
    }

    fun existsByPlatformAndToken(ctx: SvcCtx, platform: String, purchaseToken: String): Boolean =
        ctx.dsl.fetchExists(
            CORE_STORE_NOTIFICATION,
            CORE_STORE_NOTIFICATION.PLATFORM.eq(platform)
                .and(CORE_STORE_NOTIFICATION.PURCHASE_TOKEN.eq(purchaseToken))
                .and(CORE_STORE_NOTIFICATION.PROCESSED.eq(true)),
        )
}

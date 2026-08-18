package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreStoreNotification.Companion.CORE_STORE_NOTIFICATION
import com.ifmix.api.core.entity.iap.StoreNotification
import org.jooq.JSONB
import org.springframework.stereotype.Repository

@Repository
class StoreNotificationRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_STORE_NOTIFICATION,
        idField = CORE_STORE_NOTIFICATION.ID,
        appIdField = null,
        type = StoreNotification::class.java,
    )

    fun insert(ctx: SvcCtx, notification: StoreNotification) {
        val record = ctx.dsl.newRecord(CORE_STORE_NOTIFICATION, notification)
        // JSONB 字段需手动转换（entity 是 String?, Record 是 JSONB?）
        record.rawPayload = notification.rawPayload?.let { JSONB.jsonb(it) }
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

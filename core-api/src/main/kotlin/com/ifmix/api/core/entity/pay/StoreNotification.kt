package com.ifmix.api.core.entity.pay

import com.ifmix.api.core.entity.BaseAppEntity
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "pay_store_notification")
interface StoreNotification : BaseAppEntity, SoftDeletableProps {



    val platform: String?
    val subscriptionPxid: String?
    val purchaseToken: String?
    val notificationType: String?

    @Serialized
    val rawPayload: Map<String, Any?>?

    val processed: Boolean
    val processedAt: Instant?
}

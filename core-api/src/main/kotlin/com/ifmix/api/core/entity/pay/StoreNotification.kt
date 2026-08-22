package com.ifmix.api.core.entity.pay

import com.ifmix.api.core.entity.common.BaseAppEntity
import com.ifmix.api.core.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant

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

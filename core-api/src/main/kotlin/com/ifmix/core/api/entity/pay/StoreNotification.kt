package com.ifmix.core.api.entity.pay

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant

@Entity
@Table(name = "pay_store_notification")
interface StoreNotification : BaseProjectEntity, SoftDeletableProps {



    val platform: String?
    val subscriptionPxid: String?
    val purchaseToken: String?
    val notificationType: String?

    @Serialized
    val rawPayload: Map<String, Any?>?

    val processed: Boolean
    val processedAt: Instant?
}

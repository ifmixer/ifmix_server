package com.ifmix.api.core.entity.iap

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "core_store_notification")
interface StoreNotification : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    val platform: String?
    val subscriptionPxid: String?
    val purchaseToken: String?
    val notificationType: String?
    val rawPayload: String?
    val processed: Boolean
    val processedAt: Instant?
}

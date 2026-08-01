package com.ifmix.api.core.entity.iap

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import com.ifmix.api.core.service.iap.Platform
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "core_subscription")
interface Subscription : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    @Key
    val subscriptionPxid: String

    val originalTransactionId: String?
    val productId: String?
    val platform: Platform
    val active: Boolean
    val subStatus: String?
    val expiryDate: Instant?
    val purchaseToken: String?

    @Serialized
    val rawResponse: Map<String, Any?>?
}

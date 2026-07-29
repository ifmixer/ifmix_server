package com.ifmix.api.core.common.jimmer.entity.iap

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "subscription")
interface Subscription : AppScopedProps {

    @Id
    val id: UUID

    override val appId: UUID

    val subscriptionPxid: String?
    val originalTransactionId: String?
    val productId: String?
    val platform: String?
    val active: Boolean
    val subStatus: String?
    val expiryDate: Instant?
    val purchaseToken: String?
    @Serialized
    val rawResponse: Map<String, Any?>?

    val createdAt: Instant
    val updatedAt: Instant
}

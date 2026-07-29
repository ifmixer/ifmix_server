package com.ifmix.api.core.entity.iap

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "subscription")
interface Subscription : AppScopedProps {

    @Id
    val id: UUID

    override val appId: UUID

    @Key
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

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant
}

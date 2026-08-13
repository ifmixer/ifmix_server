package com.ifmix.api.core.common.entity.iap

import com.ifmix.api.core.common.entity.AppScopedProps
import com.ifmix.api.core.common.entity.SoftDeletableProps
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

    /** 购买平台编码。0=UNKNOWN, 100=APPLE, 200=GOOGLE */
    val platform: Int

    val active: Boolean
    val subStatus: String?
    val expiryDate: Instant?
    val purchaseToken: String?

    @Serialized
    val rawResponse: Map<String, Any?>?
}

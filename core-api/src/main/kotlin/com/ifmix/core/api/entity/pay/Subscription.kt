package com.ifmix.core.api.entity.pay

import com.ifmix.core.api.entity.common.BaseAppEntity
import com.ifmix.core.api.entity.common.Platform
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "pay_subscription")
interface Subscription : BaseAppEntity, SoftDeletableProps {

    /** 归属 customer（本期补，存量留空）。 */
    @Column(name = "customer_id")
    val customerId: UUID?

    @Key
    val subscriptionPxid: String

    val originalTransactionId: String?
    val productId: String?

    /** 购买平台编码。0=UNKNOWN, 10=APPLE, 20=GOOGLE */
    val platform: Platform

    val active: Boolean
    val subStatus: String?
    val expiryDate: Instant?
    val purchaseToken: String?

    @Serialized
    val rawResponse: Map<String, Any?>?
}

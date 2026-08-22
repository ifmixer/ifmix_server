package com.ifmix.api.core.entity.pay

import com.ifmix.api.core.entity.common.BaseAppEntity
import com.ifmix.api.core.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant

@Entity
@Table(name = "pay_subscription")
interface Subscription : BaseAppEntity, SoftDeletableProps {



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

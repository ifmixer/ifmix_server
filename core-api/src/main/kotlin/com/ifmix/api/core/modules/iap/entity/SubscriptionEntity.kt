package com.ifmix.api.core.modules.iap.entity

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.IndexDirection
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 用户订阅记录。每次购买/续订追加一条，
 * 通过 [subscriptionPxid] + [active] 标识当前有效订阅。
 */
@Document(collection = "subscriptions")
@CompoundIndexes(
    CompoundIndex(
        name = "sub_pxid_active_idx",
        def = "{'subscriptionPxid': 1, 'active': 1}",
        background = true,
    ),
    CompoundIndex(
        name = "sub_original_txn_idx",
        def = "{'originalTransactionId': 1}",
        background = true,
    ),
)
class SubscriptionEntity : BaseAppEntity() {

    /** 外部商店的 subscription ID（Apple subscriptionPxid / Google subscriptionId）。 */
    @Indexed
    var subscriptionPxid: String? = null

    /** 原始交易 ID（用于去重和退款查询）。索引由 sub_original_txn_idx 提供，勿加 @Indexed（会与之 key 重复冲突）。 */
    var originalTransactionId: String? = null

    /** 产品 SKU。 */
    var productId: String? = null

    /** 购买平台。 */
    var platform: Platform? = null

    /** 是否当前有效订阅。 */
    var active: Boolean = false

    /** 订阅状态。 */
    var subStatus: SubStatus? = null

    /** 过期时间。 */
    var expiryDate: java.time.Instant? = null

    /** 购买令牌（Google）或签名（Apple）。 */
    var purchaseToken: String? = null

    /** 商店返回的原始响应快照。 */
    var rawResponse: String? = null
}

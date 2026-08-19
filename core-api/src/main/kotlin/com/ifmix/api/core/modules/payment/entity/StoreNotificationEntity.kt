package com.ifmix.api.core.modules.payment.entity

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import com.ifmix.api.core.modules.payment.Platform

/**
 * 商店推送通知的落盘记录。
 * 用于 webhook 幂等——重复推送不会重复处理。
 *
 * 注意：继承 BaseAppEntity 获得 deletedAt 字段，但本集合实际不做软删。
 * 保留继承是为了与文档基类保持一致性，后续如需硬删可切换策略。
 */
@Document(collection = "store_notifications")
@CompoundIndex(
    name = "store_notif_platform_sub_idx",
    def = "{'platform': 1, 'subscriptionPxid': 1, 'processedAt': 1}",
)
class StoreNotificationEntity : BaseAppEntity() {

    /** 关联的外部商店 subscription ID。 */
    var subscriptionPxid: String? = null

    /** 通知类型。 */
    var notificationType: String? = null

    /** 商店返回的原始 payload。 */
    var rawPayload: String? = null

    /** 是否已处理。 */
    var processed: Boolean = false

    var processedAt: java.time.Instant? = null
}

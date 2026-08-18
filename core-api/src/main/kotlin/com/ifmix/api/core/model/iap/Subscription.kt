package com.ifmix.api.core.model.iap

import java.time.Instant
import java.util.UUID

/**
 * IAP 订阅记录领域模型。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射。
 * rawResponse 为 JSONB 列的 JSON 字符串表示。
 */
data class Subscription(
    val id: UUID,
    val appId: UUID,
    val subscriptionPxid: String,
    val originalTransactionId: String? = null,
    val productId: String? = null,
    val platform: Int,
    val active: Boolean,
    val subStatus: String? = null,
    val expiryDate: Instant? = null,
    val purchaseToken: String? = null,
    val rawResponse: String? = null,  // JSON string
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val deletedAt: Instant? = null,
)

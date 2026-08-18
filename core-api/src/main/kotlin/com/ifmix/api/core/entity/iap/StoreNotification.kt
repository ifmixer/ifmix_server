package com.ifmix.api.core.entity.iap

import java.time.Instant
import java.util.UUID

/**
 * 商店推送通知领域模型。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射。
 * rawPayload 为 JSONB 列的 JSON 字符串表示。
 */
data class StoreNotification(
    val id: UUID,
    val appId: UUID,
    val platform: String? = null,
    val subscriptionPxid: String? = null,
    val purchaseToken: String? = null,
    val notificationType: String? = null,
    val rawPayload: String? = null,  // JSON string
    val processed: Boolean,
    val processedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val deletedAt: Instant? = null,
)

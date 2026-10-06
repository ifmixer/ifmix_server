package com.ifmix.core.api.dto.cs

import com.ifmix.core.api.entity.common.MediaRef
import com.ifmix.core.api.entity.cs.SupportRequest
import java.util.UUID

/** 提交反馈响应（GraphQL `type SubmitFeedbackResult` 字段级一致）。 */
data class SubmitFeedbackRes(
    val id: UUID,
)

/** 创建工单响应（GraphQL `type CreateSupportRequestResult` 字段级一致）。 */
data class CreateSupportRequestRes(
    val id: UUID,
)

/**
 * 工单视图（GraphQL `type SupportRequest` 字段级一致）。DateTime 统一 ISO-8601 字符串。
 */
data class SupportRequestRes(
    val id: UUID,
    val title: String,
    val message: String,
    val email: String?,
    val phone: String?,
    /** 工单分类 Int 码。 */
    val category: Int,
    /** 工单状态 Int 码。 */
    val status: Int,
    val attachments: List<MediaRef>?,
    val locale: String?,
    val country: String?,
    val currency: String?,
    val createdAt: String,
    val updatedAt: String?,
    val firstRepliedAt: String?,
    val lastAgentRepliedAt: String?,
    val lastCustomerRepliedAt: String?,
    val resolvedAt: String?,
    val closedAt: String?,
)

/** entity → 协议出参（原 GraphQL 路径由 DGS 按 schema 字段序列化 entity，此处字段一一对应）。 */
fun SupportRequest.toRes() = SupportRequestRes(
    id = id,
    title = title,
    message = message,
    email = email,
    phone = phone,
    category = category,
    status = status,
    attachments = attachments,
    locale = locale,
    country = country,
    currency = currency,
    createdAt = createdAt.toString(),
    // 实体 updatedAt 非空（MutableProps），schema 侧声明可空只是宽松兼容
    updatedAt = updatedAt.toString(),
    firstRepliedAt = firstRepliedAt?.toString(),
    lastAgentRepliedAt = lastAgentRepliedAt?.toString(),
    lastCustomerRepliedAt = lastCustomerRepliedAt?.toString(),
    resolvedAt = resolvedAt?.toString(),
    closedAt = closedAt?.toString(),
)

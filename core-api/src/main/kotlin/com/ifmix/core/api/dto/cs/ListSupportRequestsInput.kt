package com.ifmix.core.api.dto.cs

/**
 * 「我的工单」游标列表协议入参（手写，字段与 schema/customer/cs.graphqls 的
 * `input ListSupportRequestsInput` 一致；GraphQL 侧整段可空，controller 保持可空透传）。
 */
data class ListSupportRequestsInput(
    val cursor: String? = null,
    val limit: Int? = null,
)

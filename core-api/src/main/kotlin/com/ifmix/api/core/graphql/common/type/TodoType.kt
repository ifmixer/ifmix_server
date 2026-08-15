package com.ifmix.api.core.graphql.common.type

import java.time.Instant

/** GraphQL 层 Todo 展示类型。items 字段由 DataLoader 按需填充，不在此处定义以避免循环。 */
data class TodoType(
    val id: String,
    val title: String,
    val done: Boolean,
    val meta: Map<String, Any?>?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

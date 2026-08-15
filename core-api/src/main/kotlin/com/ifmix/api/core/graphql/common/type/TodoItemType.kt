package com.ifmix.api.core.graphql.common.type

import java.time.Instant

/** GraphQL 层 TodoItem 展示类型。 */
data class TodoItemType(
    val id: String,
    val todoId: String,
    val content: String,
    val done: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

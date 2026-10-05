package com.ifmix.core.api.dto.demo

data class TodoRes(
    val id: java.util.UUID,
    val title: String,
    val done: Boolean,
    val note: String?,
    val meta: Map<String, Any?>?,
    val recommend: TodoRecommendRes?,
    val items: List<TodoItemRes>,
    val itemCount: Int,
    val pendingCount: Int,
    val finishCount: Int,
    val createdAt: String,        // Instant.toString()，ISO-8601 UTC
    val updatedAt: String?,
)
data class TodoItemRes(
    val id: java.util.UUID, val content: String, val done: Boolean,
    val note: String?, val createdAt: String, val updatedAt: String?,
)
data class TodoRecommendRes(
    val sectionId: java.util.UUID, val sectionName: String, val viewCount: Int?,
    val recItems: List<TodoRecItemRes>?,
)
data class TodoRecItemRes(
    val recId: java.util.UUID, val title: String? = null, val priority: Int,
    val createdAt: String, val updatedAt: String?,
)
data class CreateTodoRes(val todo: TodoRes)
data class UpdateTodoRes(val success: Boolean, val todo: TodoRes?)
data class UpdateTodoItemsRes(val success: Boolean)

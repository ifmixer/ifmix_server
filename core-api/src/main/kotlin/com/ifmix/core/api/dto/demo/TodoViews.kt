package com.ifmix.core.api.dto.demo

data class TodoDto(
    val id: java.util.UUID,
    val title: String,
    val done: Boolean,
    val note: String?,
    val meta: Map<String, Any?>?,
    val recommend: TodoRecommendDto?,
    val items: List<TodoItemDto>,
    val itemCount: Int,
    val pendingCount: Int,
    val finishCount: Int,
    val createdAt: String,        // Instant.toString()，ISO-8601 UTC
    val updatedAt: String?,
)
data class TodoItemDto(
    val id: java.util.UUID, val content: String, val done: Boolean,
    val note: String?, val createdAt: String, val updatedAt: String?,
)
data class TodoRecommendDto(
    val sectionId: java.util.UUID, val sectionName: String, val viewCount: Int?,
    val recItems: List<TodoRecItemDto>?,
)
data class TodoRecItemDto(
    val recId: java.util.UUID, val title: String? = null, val priority: Int,
    val createdAt: String, val updatedAt: String?,
)
data class CreateTodoResultDto(val todo: TodoDto)
data class UpdateTodoResultDto(val success: Boolean, val todo: TodoDto?)
data class UpdateTodoItemsResultDto(val success: Boolean)

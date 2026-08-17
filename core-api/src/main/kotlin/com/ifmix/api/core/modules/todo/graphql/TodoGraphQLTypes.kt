package com.ifmix.api.core.modules.todo.graphql

import java.util.UUID

/** 游标分页查询参数 */
data class TodoQueryInput(
    val cursor: String? = null,
    val limit: Int = 20,
)

// ==================== Create ====================

data class CreateTodoInput(
    val title: String,
    val done: Boolean = false,
    val note: String? = null,
    val items: List<CreateTodoItemInput>? = null,
)

data class CreateTodoItemInput(
    val content: String,
    val done: Boolean = false,
)

data class CreateTodoPayload(val todo: com.ifmix.api.core.entity.todo.Todo)

// ==================== Update Todo ====================

enum class TodoUnsetField { NOTE }

data class UpdateTodoSetInput(
    val title: String? = null,
    val done: Boolean? = null,
    val note: String? = null,
)

data class UpdateTodoInput(
    val id: UUID,
    val set: UpdateTodoSetInput? = null,
    val unset: Set<TodoUnsetField> = emptySet(),
)

data class UpdateTodoPayload(val success: Boolean, val todo: com.ifmix.api.core.entity.todo.Todo? = null)

// ==================== Update TodoItems ====================

data class CreateTodoItemForTodoInput(
    val todoId: UUID,
    val content: String,
    val done: Boolean = false,
)

data class UpdateTodoItemSetInput(
    val content: String? = null,
    val done: Boolean? = null,
)

data class UpdateTodoItemInput(
    val id: UUID,
    val set: UpdateTodoItemSetInput,
)

data class UpdateTodoItemsMutationInput(
    val create: List<CreateTodoItemForTodoInput>? = null,
    val update: List<UpdateTodoItemInput>? = null,
    val delete: List<UUID>? = null,
)

data class UpdateTodoItemsPayload(val success: Boolean)

// ==================== Delete ====================

data class DeleteTodoPayload(val success: Boolean)

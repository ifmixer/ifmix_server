package com.ifmix.api.core.modules.todo

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/** todo 模块请求/响应 DTO。时间字段对外为 epoch 毫秒。 */

data class CreateTodoItem(
    @field:NotBlank @field:Size(max = 1000) val content: String? = null,
)

data class CreateTodoRequest(
    @field:NotBlank @field:Size(max = 255) val title: String? = null,
    @field:Valid val items: List<CreateTodoItem>? = null,
)

data class UpdateTodoRequest(
    @field:Size(max = 255) val title: String? = null,
    val done: Boolean? = null,
)

data class UpdateOneTodoRequest(
    @field:NotBlank val id: String? = null,
    @field:NotNull @field:Valid val patch: UpdateTodoRequest? = null,
)

data class ByIdRequest(
    @field:NotBlank val id: String? = null,
)

data class TodoItemDto(val id: String?, val content: String?, val done: Boolean)

data class TodoDto(
    val id: String?,
    val title: String?,
    val done: Boolean,
    val items: List<TodoItemDto>,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class DeleteResult(val deleted: Boolean)

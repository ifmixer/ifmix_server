package com.ifmix.api.core.modules.todo.mapper

import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.modules.todo.document.TodoItemDocument

/** TodoDocument → GraphQL TodoType 转换。 */
fun TodoDocument.toTodoType(): TodoType = TodoType(
    id = this.id,
    title = this.title,
    done = this.done,
    meta = this.meta,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

/** TodoItemDocument → GraphQL TodoItemType 转换。 */
fun TodoItemDocument.toTodoItemType(): TodoItemType = TodoItemType(
    id = this.id,
    todoId = this.todoId,
    content = this.content,
    done = this.done,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

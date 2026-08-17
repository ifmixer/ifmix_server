package com.ifmix.api.core.modules.todo.mapper

import com.ifmix.api.core.graphql.generated.types.Todo
import com.ifmix.api.core.graphql.generated.types.TodoItem
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.modules.todo.document.TodoItemDocument

/** TodoDocument → GraphQL Todo 转换。 */
fun TodoDocument.toTodo(): Todo = Todo(
    id = this.id.toHexString(),
    title = this.title,
    done = this.done,
    meta = this.meta,
    items = emptyList(), // DataLoader 填充
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

/** TodoItemDocument → GraphQL TodoItem 转换。 */
fun TodoItemDocument.toTodoItem(): TodoItem = TodoItem(
    id = this.id.toHexString(),
    todoId = this.todoId,
    content = this.content,
    done = this.done,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

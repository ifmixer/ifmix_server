package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.graphql.generated.types.Todo
import com.ifmix.api.core.graphql.generated.types.TodoItem
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity

/** TodoEntity → GraphQL Todo 转换。 */
fun TodoEntity.toTodo(): Todo = Todo(
    id = this.id.toHexString(),
    title = this.title,
    done = this.done,
    meta = this.meta,
    items = emptyList(), // DataLoader 填充
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

/** TodoItemEntity → GraphQL TodoItem 转换。 */
fun TodoItemEntity.toTodoItem(): TodoItem = TodoItem(
    id = this.id.toHexString(),
    todoId = this.todoId.toHexString(),
    content = this.content,
    done = this.done,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

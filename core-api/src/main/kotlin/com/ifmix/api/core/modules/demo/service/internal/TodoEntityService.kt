package com.ifmix.api.core.modules.demo.service.internal

import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import java.time.Instant
import java.util.UUID

/**
 * Todo 纯业务逻辑 — 不依赖 SvcCtx。
 */
object TodoEntityService {

    fun createTime(): Instant = Instant.now()

    fun toTodo(title: String, done: Boolean): Todo =
        Todo(
            id = UuidV7.generate(),
            appId = UUID.randomUUID(), // caller sets appId
            title = title,
            done = done,
            createdAt = createTime(),
            updatedAt = createTime(),
        )

    fun toTodoItem(todoId: UUID, content: String, done: Boolean): TodoItem =
        TodoItem(
            id = UuidV7.generate(),
            appId = UUID.randomUUID(), // caller sets appId
            todoId = todoId,
            content = content,
            done = done,
            createdAt = createTime(),
            updatedAt = createTime(),
        )
}

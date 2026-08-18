package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.model.todo.Todo
import com.ifmix.api.core.model.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoCommands(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
) {
    fun create(sc: SvcCtx, input: CreateTodoInput): UUID {
        val appId = sc.op.appId!!
        val now = Instant.now()
        val id = UuidV7.generate()
        repo.insert(sc, Todo(id = id, appId = appId, installId = sc.op.installId, userId = sc.op.userId,
            title = input.title, done = input.done ?: false, createdAt = now))
        input.items?.takeIf { it.isNotEmpty() }?.let { items ->
            itemRepo.batchInsert(sc, items.map { i ->
                TodoItem(id = UuidV7.generate(), appId = appId, todoId = id,
                    content = i.content, done = i.done ?: false, createdAt = now)
            })
        }
        return id
    }

    fun update(sc: SvcCtx, input: UpdateTodoInput): Boolean {
        val appId = sc.op.appId!!
        if (!repo.exists(sc, appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        repo.partialUpdate(sc, appId, input)
        return true
    }

    fun delete(sc: SvcCtx, id: UUID): Boolean {
        val appId = sc.op.appId!!
        repo.deleteById(sc, appId, id)
        return true
    }

    fun batchDelete(sc: SvcCtx, ids: List<UUID>): Int {
        val appId = sc.op.appId!!
        ids.forEach { repo.deleteById(sc, appId, it) }
        return ids.size
    }
}

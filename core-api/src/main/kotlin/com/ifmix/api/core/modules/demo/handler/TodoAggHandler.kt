package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.mybatis.model.Todo
import com.ifmix.api.core.generated.mybatis.model.TodoItem
import com.ifmix.api.core.generated.types.CreateTodoItemForTodoInput
import com.ifmix.api.core.generated.types.CreateTodoItemInput
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoAggHandler(
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
) {

    // --- Queries ---

    fun findById(mc: ModuleCtx, appId: UUID, id: UUID): Todo? =
        todoRepo.findById(appId, id)

    fun findByIds(mc: ModuleCtx, appId: UUID, ids: List<UUID>): List<Todo> =
        todoRepo.findByIds(appId, ids)

    fun findByCursor(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> =
        todoRepo.findByCursor(appId, cursor, limit, filter)

    // --- Mutations ---

    fun create(mc: ModuleCtx, title: String, done: Boolean?, note: String?, items: List<CreateTodoItemInput>?): Todo {
        val appId = mc.mustGetAppId()
        val now = Instant.now()
        val id = UuidV7.generate()
        val todo = Todo(
            id = id,
            appId = appId,
            title = title,
            done = done ?: false,
            note = note,
            meta = null,
            installId = mc.installId,
            userId = mc.userId,
            createdAt = now,
            updatedAt = now,
        )
        todoRepo.insert(todo)

        items?.forEach { item ->
            val todoItem = TodoItem(
                id = UuidV7.generate(),
                appId = appId,
                todoId = id,
                content = item.content,
                done = item.done ?: false,
                createdAt = now,
                updatedAt = now,
            )
            todoItemRepo.insert(todoItem)
        }

        return todo
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoInput) {
        todoRepo.partialUpdate(appId, input)
    }

    fun batchUpdateItems(mc: ModuleCtx, appId: UUID, input: UpdateTodoItemsMutationInput) {
        input.delete?.let { ids ->
            if (ids.isNotEmpty()) todoItemRepo.deleteByIds(appId, ids)
        }

        input.create?.forEach { item ->
            createItem(appId, item)
        }

        input.update?.forEach { entry ->
            todoItemRepo.partialUpdate(appId, entry)
        }
    }

    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean =
        todoRepo.deleteById(appId, id)

    fun batchDelete(mc: ModuleCtx, appId: UUID, ids: List<UUID>): Int =
        todoRepo.deleteByIds(appId, ids)

    private fun createItem(appId: UUID, item: CreateTodoItemForTodoInput) {
        val now = Instant.now()
        val todoItem = TodoItem(
            id = UuidV7.generate(),
            appId = appId,
            todoId = item.todoId,
            content = item.content,
            done = item.done ?: false,
            createdAt = now,
            updatedAt = now,
        )
        todoItemRepo.insert(todoItem)
    }
}

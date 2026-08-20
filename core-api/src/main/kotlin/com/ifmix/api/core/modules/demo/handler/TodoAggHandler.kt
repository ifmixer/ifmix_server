package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.generated.types.CreateTodoItemForTodoInput
import com.ifmix.api.core.generated.types.CreateTodoItemInput
import com.ifmix.api.core.generated.types.FilterGroup
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
        todoRepo.findById(mc, appId, id)

    fun findByIds(mc: ModuleCtx, appId: UUID, ids: List<UUID>): List<Todo> =
        todoRepo.findByIds(mc, appId, ids)

    fun findByCursor(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> =
        todoRepo.findByCursor(mc, appId, cursor, limit, filter)

    fun findByFilter(mc: ModuleCtx, appId: UUID, filter: FilterGroup?, cursor: UUID?, limit: Int): Page<Todo> =
        todoRepo.findByFilter(mc, appId, filter, cursor, limit)

    // --- Mutations ---

    fun create(mc: ModuleCtx, title: String, done: Boolean?, note: String?, items: List<CreateTodoItemInput>?): Todo {
        val appId = mc.mustGetAppId()
        val now = Instant.now()
        val id = UuidV7.generate()
        val todo = Todo {
            this.id = id
            this.appId = appId
            this.title = title
            this.done = done ?: false
            this.note = note
            this.meta = null
            this.installId = mc.installId
            this.userId = mc.userId
            this.createdAt = now
            this.updatedAt = now
        }
        val saved = todoRepo.save(mc, todo)

        items?.forEach { item ->
            val todoItem = TodoItem {
                this.id = UuidV7.generate()
                this.appId = appId
                this.todoId = id
                this.content = item.content
                this.done = item.done ?: false
                this.note = item.note
                this.createdAt = now
                this.updatedAt = now
            }
            todoItemRepo.save(mc, todoItem)
        }

        return saved
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoInput) {
        todoRepo.partialUpdate(mc, appId, input)
    }

    fun batchUpdateItems(mc: ModuleCtx, appId: UUID, input: UpdateTodoItemsMutationInput) {
        // Delete
        input.delete?.let { ids ->
            if (ids.isNotEmpty()) todoItemRepo.deleteByIds(mc, appId, ids)
        }

        // Create
        input.create?.forEach { item ->
            createItem(mc, appId, item)
        }

        // Update
        input.update?.forEach { entry ->
            todoItemRepo.partialUpdate(mc, appId, entry)
        }
    }

    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean =
        todoRepo.deleteById(mc, appId, id)

    fun batchDelete(mc: ModuleCtx, appId: UUID, ids: List<UUID>): Int =
        todoRepo.deleteByIds(mc, appId, ids)

    private fun createItem(mc: ModuleCtx, appId: UUID, item: CreateTodoItemForTodoInput) {
        val now = Instant.now()
        val todoItem = TodoItem {
            this.id = UuidV7.generate()
            this.appId = appId
            this.todoId = item.todoId
            this.content = item.content
            this.done = item.done ?: false
            this.note = item.note
            this.createdAt = now
            this.updatedAt = now
        }
        todoItemRepo.save(mc, todoItem)
    }
}

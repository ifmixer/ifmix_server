package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.generated.types.CreateTodoItemForTodoInput
import com.ifmix.api.core.generated.types.CreateTodoItemInput
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoHandler(
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
) {

    // --- Queries ---

    fun findById(sc: SvcCtx, appId: UUID, id: UUID): Todo? =
        todoRepo.findById(sc, appId, id)

    fun findByIds(sc: SvcCtx, appId: UUID, ids: List<UUID>): List<Todo> =
        todoRepo.findByIds(sc, appId, ids)

    fun findByCursor(sc: SvcCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> =
        todoRepo.findByCursor(sc, appId, cursor, limit, filter)

    fun findByFilter(sc: SvcCtx, appId: UUID, filter: FilterGroup?, cursor: UUID?, limit: Int): Page<Todo> =
        todoRepo.findByFilter(sc, appId, filter, cursor, limit)

    // --- Mutations ---

    fun create(sc: SvcCtx, title: String, done: Boolean?, note: String?, items: List<CreateTodoItemInput>?): Todo {
        val appId = sc.mustGetAppId()
        val now = Instant.now()
        val id = UuidV7.generate()
        val todo = Todo {
            this.id = id
            this.appId = appId
            this.title = title
            this.done = done ?: false
            this.note = note
            this.installId = sc.installId
            this.userId = sc.userId
            this.createdAt = now
            this.updatedAt = now
        }
        val saved = todoRepo.save(sc, todo)

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
            todoItemRepo.save(sc, todoItem)
        }

        return saved
    }

    fun partialUpdate(sc: SvcCtx, appId: UUID, input: UpdateTodoInput) {
        todoRepo.partialUpdate(sc, appId, input)
    }

    fun batchUpdateItems(sc: SvcCtx, appId: UUID, input: UpdateTodoItemsMutationInput) {
        // Delete
        input.delete?.let { ids ->
            if (ids.isNotEmpty()) todoItemRepo.deleteByIds(sc, appId, ids)
        }

        // Create
        input.create?.forEach { item ->
            createItem(sc, appId, item)
        }

        // Update
        input.update?.forEach { entry ->
            todoItemRepo.partialUpdate(sc, appId, entry)
        }
    }

    fun deleteById(sc: SvcCtx, appId: UUID, id: UUID): Boolean =
        todoRepo.deleteById(sc, appId, id)

    fun batchDelete(sc: SvcCtx, appId: UUID, ids: List<UUID>): Int =
        todoRepo.deleteByIds(sc, appId, ids)

    private fun createItem(sc: SvcCtx, appId: UUID, item: CreateTodoItemForTodoInput) {
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
        todoItemRepo.save(sc, todoItem)
    }
}

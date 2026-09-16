package com.ifmix.core.api.modules.demo.handler

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.entity.demo.toDomain
import com.ifmix.core.api.generated.types.CreateTodoInput
import com.ifmix.core.api.generated.types.CreateTodoItemForTodoInput
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.UpdateTodoInput
import com.ifmix.core.api.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.modules.demo.repo.TodoItemRepository
import com.ifmix.core.api.modules.demo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoAggHandler(
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
) {

    // --- Queries ---

    fun findById(mc: ModuleCtx, projectId: String, id: UUID): Todo? =
        todoRepo.findById(mc, projectId, id)

    fun findByIds(mc: ModuleCtx, projectId: String, ids: List<UUID>): List<Todo> =
        todoRepo.findByIds(mc, projectId, ids)

    fun findTodos(mc: ModuleCtx, projectId: String, findOptions: CommonFindOptions?): Page<Todo> =
        todoRepo.findByOptions(mc, projectId, findOptions)

    fun findItemsByTodoIds(mc: ModuleCtx, projectId: String, todoIds: Collection<UUID>): List<TodoItem> =
        todoItemRepo.findByTodoIds(mc, projectId, todoIds)

    fun countItemsByTodoIds(mc: ModuleCtx, projectId: String, todoIds: Collection<UUID>) =
        todoItemRepo.countByTodoIds(mc, projectId, todoIds)

    // --- Mutations ---

    fun create(mc: ModuleCtx, input: CreateTodoInput): Todo {
        val projectId = mc.mustGetProjectId()
        val now = Instant.now()
        val id = UuidV7.generate()
        val todo = Todo {
            this.id = id
            this.projectId = projectId
            this.title = input.title
            this.done = input.done ?: false
            this.note = input.note
            this.meta = null
            this.recommend = input.recommend?.toDomain()
            this.customerId = mc.actorId
            this.createdAt = now
            this.updatedAt = now
        }
        todoRepo.save(mc, todo)

        input.items?.forEach { item ->
            val todoItem = TodoItem {
                this.id = UuidV7.generate()
                this.projectId = projectId
                this.todoId = id
                this.content = item.content
                this.done = item.done ?: false
                this.note = item.note
                this.createdAt = now
                this.updatedAt = now
            }
            todoItemRepo.save(mc, todoItem)
        }

        return todoRepo.findById(mc, projectId, id)!!
    }

    fun partialUpdate(mc: ModuleCtx, projectId: String, input: UpdateTodoInput) {
        todoRepo.partialUpdate(mc, projectId, input)
    }

    fun batchUpdateItems(mc: ModuleCtx, projectId: String, input: UpdateTodoItemsMutationInput) {
        // Delete
        input.delete?.let { ids ->
            if (ids.isNotEmpty()) todoItemRepo.deleteByIds(mc, projectId, ids)
        }

        // Create
        input.create?.forEach { item ->
            createItem(mc, projectId, item)
        }

        // Update
        input.update?.forEach { entry ->
            todoItemRepo.partialUpdate(mc, projectId, entry)
        }
    }

    fun deleteById(mc: ModuleCtx, projectId: String, id: UUID): Boolean =
        todoRepo.deleteById(mc, projectId, id)

    fun deleteByIds(mc: ModuleCtx, projectId: String, ids: List<UUID>): Int =
        todoRepo.deleteByIds(mc, projectId, ids)

    private fun createItem(mc: ModuleCtx, projectId: String, item: CreateTodoItemForTodoInput) {
        val now = Instant.now()
        val todoItem = TodoItem {
            this.id = UuidV7.generate()
            this.projectId = projectId
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

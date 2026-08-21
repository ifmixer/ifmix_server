package com.ifmix.api.core.modules.demo.service.internal

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.TodoWithStats
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.CreateTodoItemForTodoInput
import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.jooq.tables.CoreTodo.Companion.CORE_TODO
import com.ifmix.api.core.jooq.tables.CoreTodoItem.Companion.CORE_TODO_ITEM
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoEntityService(
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
) {
    // ===== Todo CRUD =====

    fun createTodo(sc: SvcCtx, input: CreateTodoInput): Todo {
        val now = Instant.now()
        val todoId = UuidV7.generate()
        val todo = Todo(
            id = todoId,
            appId = sc.mustGetAppId(),
            installId = sc.installId,
            userId = sc.userId,
            title = input.title,
            done = input.done ?: false,
            createdAt = now,
            updatedAt = now,
        )
        todoRepo.insert(sc, todo)

        // 嵌入创建子项
        input.items?.forEach { itemInput ->
            val item = TodoItem(
                id = UuidV7.generate(),
                appId = sc.mustGetAppId(),
                todoId = todoId,
                content = itemInput.content,
                done = itemInput.done ?: false,
                createdAt = now,
                updatedAt = now,
            )
            todoItemRepo.insert(sc, item)
        }
        return todo
    }

    fun updateTodo(sc: SvcCtx, input: UpdateTodoInput): Todo {
        val appId = sc.mustGetAppId()
        val todo = todoRepo.findById(sc, appId, input.id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "Todo not found")
        checkOwnership(sc, todo)

        val now = Instant.now()
        todoRepo.partialUpdate(sc, appId, input.id) {
            set(CORE_TODO.UPDATED_AT, now)
            input.set?.title?.let { set(CORE_TODO.TITLE, it) }
            input.set?.done?.let { set(CORE_TODO.DONE, it) }
            input.set?.note?.let { set(CORE_TODO.NOTE, it) }
            input.unset?.forEach { field ->
                when (field) {
                    TodoUnsetField.NOTE -> set(CORE_TODO.NOTE, null as String?)
                }
            }
        }
        // 返回更新后的快照
        return todoRepo.findById(sc, appId, input.id)!!
    }

    fun findTodoById(sc: SvcCtx, id: UUID): Todo {
        val appId = sc.mustGetAppId()
        return todoRepo.findById(sc, appId, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "Todo not found")
    }

    fun findTodosByIds(sc: SvcCtx, ids: List<UUID>): List<Todo> {
        val appId = sc.mustGetAppId()
        return todoRepo.findByIds(sc, appId, ids)
    }

    fun findTodosByCursor(sc: SvcCtx, cursor: String?, limit: Int?): Page<Todo> {
        val appId = sc.mustGetAppId()
        val effectiveLimit = limit ?: 20
        val cursorUuid = cursor?.let { UUID.fromString(it) }
        return todoRepo.findByCursor(sc, appId, cursorUuid, effectiveLimit)
    }

    fun deleteTodo(sc: SvcCtx, id: UUID): Boolean {
        val appId = sc.mustGetAppId()
        val todo = todoRepo.findById(sc, appId, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "Todo not found")
        checkOwnership(sc, todo)
        todoItemRepo.deleteByTodoId(sc, appId, id)
        return todoRepo.deleteById(sc, appId, id)
    }

    fun batchDeleteTodos(sc: SvcCtx, ids: List<UUID>): Boolean {
        val appId = sc.mustGetAppId()
        ids.forEach { id -> todoItemRepo.deleteByTodoId(sc, appId, id) }
        todoRepo.deleteByIds(sc, appId, ids)
        return true
    }

    // ===== TodoItem batch operations =====

    fun batchUpdateTodoItems(
        sc: SvcCtx,
        creates: List<CreateTodoItemForTodoInput>?,
        updates: List<UpdateTodoItemInput>?,
        deletes: List<UUID>?,
    ) {
        val appId = sc.mustGetAppId()
        val now = Instant.now()

        creates?.forEach { input ->
            val item = TodoItem(
                id = UuidV7.generate(),
                appId = appId,
                todoId = input.todoId,
                content = input.content,
                done = input.done ?: false,
                createdAt = now,
                updatedAt = now,
            )
            todoItemRepo.insert(sc, item)
        }

        updates?.forEach { input ->
            todoItemRepo.partialUpdate(sc, appId, input.id) {
                set(CORE_TODO_ITEM.UPDATED_AT, now)
                input.set?.content?.let { set(CORE_TODO_ITEM.CONTENT, it) }
                input.set?.done?.let { set(CORE_TODO_ITEM.DONE, it) }
                // ponytail: note 列待 Flyway migration 加到 core_todo_item 后启用
                // input.set?.note?.let { set(CORE_TODO_ITEM.NOTE, it) }
                input.unset?.forEach { field ->
                    when (field) {
                        TodoItemUnsetField.NOTE -> {} // noop until column exists
                    }
                }
            }
        }

        if (!deletes.isNullOrEmpty()) {
            todoItemRepo.deleteByIds(sc, appId, deletes)
        }
    }

    // ===== JOIN 演示 =====

    fun findTodosWithStats(sc: SvcCtx, cursor: String?, limit: Int?): Page<TodoWithStats> {
        val appId = sc.mustGetAppId()
        val effectiveLimit = limit ?: 20
        val cursorUuid = cursor?.let { UUID.fromString(it) }
        return todoRepo.findWithStatsByCursor(sc, appId, cursorUuid, effectiveLimit)
    }

    // ===== DataLoader support =====

    fun findItemsByTodoIds(sc: SvcCtx, todoIds: Collection<UUID>): Map<UUID, List<TodoItem>> {
        val appId = sc.mustGetAppId()
        return todoItemRepo.findByTodoIds(sc, appId, todoIds).groupBy { it.todoId }
    }

    // ===== Internal =====

    private fun checkOwnership(sc: SvcCtx, todo: Todo) {
        if (!todo.isOwned(sc.userId, sc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN, "Not allowed")
        }
    }
}

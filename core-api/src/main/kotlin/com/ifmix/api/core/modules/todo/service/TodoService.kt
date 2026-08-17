package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.redis.CacheAside
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Todo 业务逻辑层。
 *
 * 职责：业务编排 + 缓存管理。
 * 所有 SQL 委托给 TodoRepository。
 */
@Service
class TodoService(
    private val repo: TodoRepository,
    private val cache: CacheAside,
) {

    // ===== Query =====

    fun findById(ctx: OperationContext, id: UUID): Todo? {
        val appId = ctx.mustGetAppId()
        return cache.getOrLoadNullable(cacheKey(appId, id), Todo::class.java) {
            repo.findById(appId, id)
        }
    }

    fun findByCursor(ctx: OperationContext, input: TodoQueryInput): Page<Todo> {
        val appId = ctx.mustGetAppId()
        val limit = (input.limit ?: 20).coerceIn(1, 100)
        val cursorUuid = input.cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val items = repo.findByCursor(appId, cursorUuid, limit + 1)
        val hasMore = items.size > limit
        val resultItems = items.take(limit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.id?.toString(),
            hasMore = hasMore,
        )
    }

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<Todo> {
        if (ids.isEmpty()) return emptyList()
        val appId = ctx.mustGetAppId()
        return cache.loadMany(
            ids = ids.map { it.toString() },
            keyOf = { cacheKey(appId, UUID.fromString(it)) },
            type = Todo::class.java,
            idOf = { it.id.toString() },
        ) { missIds ->
            repo.findByIds(appId, missIds.map { UUID.fromString(it) })
        }
    }

    // ===== Mutation: Create =====

    @Transactional
    fun createTodo(ctx: OperationContext, input: CreateTodoInput): UUID {
        val appId = ctx.mustGetAppId()
        val id = UuidV7.generate()
        val entity = Todo {
            this.id = id
            this.appId = appId
            this.installId = ctx.installId
            this.userId = ctx.userId
            this.title = input.title
            this.done = input.done ?: false
            this.note = input.note
            this.meta = null
        }
        repo.insert(entity)
        input.items?.takeIf { it.isNotEmpty() }?.let { items ->
            val itemEntities = items.map { itemInput ->
                TodoItem {
                    this.id = UuidV7.generate()
                    this.appId = appId
                    this.content = itemInput.content
                    this.done = itemInput.done ?: false
                    this.note = itemInput.note
                }
            }
            repo.saveItems(itemEntities)
        }
        return id
    }

    // ===== Mutation: Update Todo =====

    @Transactional
    fun updateTodo(ctx: OperationContext, input: UpdateTodoInput): Boolean {
        val appId = ctx.mustGetAppId()
        // 校验存在性
        if (!repo.exists(appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)

        // 直接构造 partial entity — Jimmer 只 UPDATE 被赋值的列
        val entity = Todo {
            this.id = input.id
            this.appId = appId
            input.set?.title?.let { this.title = it }
            input.set?.done?.let { this.done = it }
            input.set?.note?.let { this.note = it }
            if (input.unset?.contains(TodoUnsetField.NOTE) == true) {
                this.note = null
            }
        }
        repo.save(entity)
        cache.evict(cacheKey(appId, input.id))
        return true
    }

    // ===== Mutation: Update TodoItems =====

    @Transactional
    fun updateTodoItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput) {
        val appId = ctx.mustGetAppId()

        // Create + Update 合并为一次 saveEntities（Jimmer 按 id 自动区分 INSERT/UPDATE）
        val entities = buildList<TodoItem> {
            input.create?.forEach { c ->
                add(TodoItem {
                    this.id = UuidV7.generate()
                    this.appId = appId
                    this.content = c.content
                    this.done = c.done ?: false
                    this.note = c.note
                })
            }
            input.update?.forEach { u ->
                add(TodoItem {
                    this.id = u.id
                    this.appId = appId
                    u.set?.content?.let { this.content = it }
                    u.set?.done?.let { this.done = it }
                    u.set?.note?.let { this.note = it }
                    if (u.unset?.contains(TodoItemUnsetField.NOTE) == true) {
                        this.note = null
                    }
                })
            }
        }
        if (entities.isNotEmpty()) {
            repo.saveItems(entities)
        }

        // Delete
        input.delete?.takeIf { it.isNotEmpty() }?.let { ids ->
            repo.deleteItemsByIds(appId, ids)
        }
    }

    // ===== Mutation: Delete =====

    @Transactional
    fun deleteTodo(ctx: OperationContext, id: UUID): Boolean {
        val appId = ctx.mustGetAppId()
        val deleted = repo.deleteTodo(appId, id)
        if (deleted) cache.evict(cacheKey(appId, id))
        return deleted
    }

    @Transactional
    fun deleteTodosByIds(ctx: OperationContext, ids: List<UUID>): Int {
        val appId = ctx.mustGetAppId()
        if (ids.isEmpty()) return 0
        val count = repo.deleteTodosByIds(appId, ids)
        cache.evictAll(ids.map { cacheKey(appId, it) })
        return count
    }

    // ===== Internal =====

    private fun cacheKey(appId: UUID, id: UUID) = "todo:$appId:$id"
}

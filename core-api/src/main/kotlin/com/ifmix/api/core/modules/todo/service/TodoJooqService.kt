package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.redis.CacheAside
import com.ifmix.api.core.model.Todo
import com.ifmix.api.core.model.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoJooqRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * jOOQ 版 TodoService。
 *
 * 与原 Jimmer 版 TodoService 并存，供逐步切换。
 * 职责：业务编排 + 缓存管理。所有 SQL 委托给 TodoJooqRepository。
 */
@Service("todoJooqService")
class TodoJooqService(
    private val repo: TodoJooqRepository,
    private val cache: CacheAside,
) {

    // ===== Query =====

    fun findById(ctx: OperationContext, id: UUID): Todo? {
        val appId = ctx.mustGetAppId()
        return if (ctx.readCache) {
            cache.getOrLoadNullable(cacheKey(appId, id), Todo::class.java) {
                repo.findById(appId, id)
            }
        } else {
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
        if (!ctx.readCache) return repo.findByIds(appId, ids)
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
        repo.insert(
            appId = appId,
            id = id,
            installId = ctx.installId,
            userId = ctx.userId,
            title = input.title,
            done = input.done ?: false,
            note = input.note,
        )
        input.items?.takeIf { it.isNotEmpty() }?.let { items ->
            val now = Instant.now()
            repo.saveItems(appId, items.map { i ->
                TodoItem(
                    id = UuidV7.generate(),
                    appId = appId,
                    todoId = id,
                    content = i.content,
                    done = i.done ?: false,
                    note = i.note,
                    createdAt = now,
                )
            })
        }
        return id
    }

    // ===== Mutation: Update Todo =====

    @Transactional
    fun updateTodo(ctx: OperationContext, input: UpdateTodoInput): Boolean {
        val appId = ctx.mustGetAppId()
        if (!repo.exists(appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        repo.partialUpdate(appId, input)
        cache.evict(cacheKey(appId, input.id))
        return true
    }

    // ===== Mutation: Update TodoItems =====

    @Transactional
    fun updateTodoItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput) {
        val appId = ctx.mustGetAppId()
        val now = Instant.now()

        // Create
        input.create?.takeIf { it.isNotEmpty() }?.let { creates ->
            repo.saveItems(appId, creates.map { c ->
                TodoItem(
                    id = UuidV7.generate(),
                    appId = appId,
                    todoId = c.todoId,
                    content = c.content,
                    done = c.done ?: false,
                    note = c.note,
                    createdAt = now,
                )
            })
        }

        // Update (partial, with set/unset)
        input.update?.takeIf { it.isNotEmpty() }?.let { updates ->
            repo.updateItems(appId, updates)
        }

        // Delete (soft)
        input.delete?.takeIf { it.isNotEmpty() }?.let { ids ->
            repo.deleteItemsByIds(appId, ids)
        }
    }

    // ===== Mutation: Delete =====

    @Transactional
    fun deleteTodo(ctx: OperationContext, id: UUID): Boolean {
        val appId = ctx.mustGetAppId()
        val deleted = repo.deleteById(appId, id)
        if (deleted) cache.evict(cacheKey(appId, id))
        return deleted
    }

    @Transactional
    fun deleteTodosByIds(ctx: OperationContext, ids: List<UUID>): Int {
        val appId = ctx.mustGetAppId()
        if (ids.isEmpty()) return 0
        val count = repo.deleteByIds(appId, ids)
        cache.evictAll(ids.map { cacheKey(appId, it) })
        return count
    }

    // ===== Internal =====

    private fun cacheKey(appId: UUID, id: UUID) = "todo:$appId:$id"
}

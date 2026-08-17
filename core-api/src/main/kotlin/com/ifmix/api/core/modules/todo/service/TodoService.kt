package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.copy
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.CursorQueryInput
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.graphql.FetcherBuilder
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.todo.graphql.*
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Todo 业务逻辑层。
 *
 * GraphQL 方法使用 Jimmer entity interface + Fetcher 动态投影，
 * REST 方法保持向后兼容（使用 Jimmer DTO view）。
 */
@Service
class TodoService(
    private val todoRepo: TodoRepository,
    private val sql: KSqlClient,
    private val fetcherBuilder: FetcherBuilder,
) {

    // ===== GraphQL Query methods =====

    /**
     * 根据 ID 查询单个 Todo（返回 entity interface，由 fetcher 决定投影形状）。
     */
    fun findById(ctx: OperationContext, id: UUID, fetcher: Fetcher<Todo>): Todo? {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            select(table.fetch(fetcher))
        }.limit(1).execute().firstOrNull()
    }

    /**
     * 游标分页查询 Todo 列表（GraphQL 版本）。
     */
    fun findByCursor(
        ctx: OperationContext,
        input: TodoQueryInput,
        fetcher: Fetcher<Todo>,
    ): Page<Todo> {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        val limit = input.limit.coerceIn(1, 100)
        val cursorUuid = input.cursor?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            if (cursorUuid != null) {
                where(table.id lt cursorUuid)
            }
            orderBy(table.id.desc())
            select(table.fetch(fetcher))
        }.limit(limit + 1).execute().let { items ->
            val hasMore = items.size > limit
            val resultItems = items.take(limit)
            Page(
                items = resultItems,
                nextCursor = resultItems.lastOrNull()?.id?.toString(),
                hasMore = hasMore,
            )
        }
    }

    /**
     * 批量根据 ID 查询 Todo（GraphQL 版本）。
     */
    fun findByIds(ctx: OperationContext, ids: List<UUID>, fetcher: Fetcher<Todo>): List<Todo> {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        if (ids.isEmpty()) return emptyList()
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id valueIn ids)
            select(table.fetch(fetcher))
        }.execute()
    }

    // ===== GraphQL Mutation: Create =====

    @Transactional
    fun createTodo(ctx: OperationContext, input: CreateTodoInput): UUID {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        val id = UuidV7.generate()
        val entity = Todo {
            this.id = id
            this.appId = appId
            this.installId = ctx.installId
            this.userId = ctx.userId
            this.title = input.title
            this.done = input.done
            this.note = input.note
            this.meta = null
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }
        input.items?.forEach { itemInput ->
            val itemDraft = TodoItem {
                this.id = UuidV7.generate()
                this.appId = appId
                this.content = itemInput.content
                this.done = itemInput.done
            }
            sql.entities.save(itemDraft)
        }
        return id
    }

    // ===== GraphQL Mutation: Update Todo =====

    @Transactional
    fun updateTodo(ctx: OperationContext, input: UpdateTodoInput): Boolean {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        val existing = findById(ctx, input.id, fetcherBuilder.minimalFetcher(Todo::class))
            ?: throw ApiError(ErrorCode.NOT_FOUND)
        val draft = existing.copy {
            input.set?.let { set ->
                set.title?.let { this.title = it }
                set.done?.let { this.done = it }
                set.note?.let { this.note = it }
            }
            if (input.unset.contains(TodoUnsetField.NOTE)) {
                this.note = null
            }
        }
        sql.entities.save(draft)
        return true
    }

    // ===== GraphQL Mutation: Update TodoItems =====

    @Transactional
    fun updateTodoItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput) {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")

        // Create new items
        input.create?.forEach { createInput ->
            val todoExists = sql.createQuery(Todo::class) {
                where(table.appId eq appId)
                where(table.id eq createInput.todoId)
                select(table)
            }.limit(1).execute().isNotEmpty()
            if (!todoExists) throw ApiError(ErrorCode.NOT_FOUND)

            val itemDraft = TodoItem {
                this.id = UuidV7.generate()
                this.appId = appId
                this.content = createInput.content
                this.done = createInput.done
            }
            sql.entities.save(itemDraft)
        }

        // Update existing items
        input.update?.forEach { updateInput ->
            val existing = sql.createQuery(TodoItem::class) {
                where(table.id eq updateInput.id)
                select(table)
            }.limit(1).execute().firstOrNull()
                ?: throw ApiError(ErrorCode.NOT_FOUND)
            val draft = existing.copy {
                updateInput.set.content?.let { this.content = it }
                updateInput.set.done?.let { this.done = it }
            }
            sql.entities.save(draft)
        }

        // Delete items
        input.delete?.forEach { itemId ->
            val count = sql.createDelete(TodoItem::class) {
                where(table.id eq itemId)
            }.execute()
            if (count == 0) throw ApiError(ErrorCode.NOT_FOUND)
        }
    }

    // ===== GraphQL Mutation: Delete =====

    @Transactional
    fun deleteTodo(ctx: OperationContext, id: UUID): Boolean {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        return todoRepo.deleteTodo(ctx.repoCtx, appId, id)
    }

    @Transactional
    fun deleteTodosByIds(ctx: OperationContext, ids: List<UUID>): Int {
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        if (ids.isEmpty()) return 0
        return todoRepo.deleteTodosByIds(ctx.repoCtx, appId, ids)
    }

    // ===== Legacy REST methods (kept for backward compatibility) =====

    fun findTodoByCursor(ctx: OperationContext, input: CursorQueryInput) =
        todoRepo.findViewByCursor(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), com.ifmix.api.core.entity.todo.dto.TodoListDto::class, input)

    fun getTodo(ctx: OperationContext, id: UUID): com.ifmix.api.core.entity.todo.dto.TodoDetailDto =
        todoRepo.findTodoById(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), id)
            ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional
    fun createOne(ctx: OperationContext, input: com.ifmix.api.core.entity.todo.dto.TodoCreateInput): UUID =
        todoRepo.create(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), ctx.installId, ctx.userId, input)

    @Transactional
    fun updateOne(ctx: OperationContext, input: com.ifmix.api.core.entity.todo.dto.TodoUpdateInput): Boolean =
        todoRepo.update(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), input)

    @Transactional
    fun deleteOne(ctx: OperationContext, id: UUID): Boolean =
        todoRepo.deleteTodo(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), id)

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<com.ifmix.api.core.entity.todo.dto.TodoListDto> =
        todoRepo.findTodoByIds(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), ids)

    @Transactional
    fun updateByIds(ctx: OperationContext, inputs: List<com.ifmix.api.core.entity.todo.dto.TodoUpdateInput>): Int {
        if (inputs.isEmpty()) return 0
        return todoRepo.batchUpdate(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), inputs)
    }

    @Transactional
    fun deleteItems(ctx: OperationContext, itemIds: List<UUID>): Int =
        todoRepo.deleteItemsByIds(ctx.repoCtx, ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST), itemIds)
}

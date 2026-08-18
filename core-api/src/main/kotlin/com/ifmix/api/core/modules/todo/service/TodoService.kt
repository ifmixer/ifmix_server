package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.infra.service.CrudServiceOps
import com.ifmix.api.core.infra.service.CrudServiceOpsFactory
import com.ifmix.api.core.model.todo.Todo
import com.ifmix.api.core.model.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class TodoService(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    private val tx: TxRunner,
    factory: CrudServiceOpsFactory,
) {
    private val ops: CrudServiceOps<Todo> = factory.create(Todo::class.java, "todo") { it.id }

    // ===== Query =====

    fun findById(ctx: OperationContext, id: UUID): Todo? =
        ops.findById(ctx, id, repo::findById)

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<Todo> =
        ops.findByIds(ctx, ids, repo::findByIds)

    fun findByCursor(ctx: OperationContext, input: TodoQueryInput): Page<Todo> =
        ops.findByCursor(ctx, input.cursor, input.limit, repo::findByCursor)

    // ===== Mutation: Create =====

    fun createTodo(ctx: OperationContext, input: CreateTodoInput): UUID = tx.withTx(ctx) { txCtx ->
        val appId = txCtx.mustGetAppId()
        val now = Instant.now()
        val id = UuidV7.generate()
        repo.insert(
            txCtx.repoCtx, Todo(
                id = id, appId = appId,
                installId = txCtx.installId, userId = txCtx.userId,
                title = input.title, done = input.done ?: false,
                createdAt = now,
            )
        )
        input.items?.takeIf { it.isNotEmpty() }?.let { items ->
            itemRepo.batchInsert(txCtx.repoCtx, items.map { i ->
                TodoItem(
                    id = UuidV7.generate(), appId = appId, todoId = id,
                    content = i.content, done = i.done ?: false,
                    createdAt = now,
                )
            })
        }
        id
    }

    // ===== Mutation: Update =====

    fun updateTodo(ctx: OperationContext, input: UpdateTodoInput): Boolean = tx.withTx(ctx) { txCtx ->
        val appId = txCtx.mustGetAppId()
        if (!repo.exists(txCtx.repoCtx, appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        repo.partialUpdate(txCtx.repoCtx, appId, input)
        ops.evict(txCtx, input.id)
        true
    }

    // ===== Mutation: Delete =====

    fun deleteTodo(ctx: OperationContext, id: UUID): Boolean = tx.withTx(ctx) { txCtx ->
        ops.deleteById(txCtx, id, repo::deleteById)
    }

    fun deleteTodosByIds(ctx: OperationContext, ids: List<UUID>): Int = tx.withTx(ctx) { txCtx ->
        ops.deleteByIds(txCtx, ids, repo::deleteByIds)
    }
}

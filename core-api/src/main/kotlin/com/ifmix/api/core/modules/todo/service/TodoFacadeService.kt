package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.infra.service.CrudServiceOps
import com.ifmix.api.core.infra.service.CrudServiceOpsFactory
import com.ifmix.api.core.model.todo.Todo
import com.ifmix.api.core.model.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class TodoFacadeService(
    private val queries: TodoQueries,
    private val commands: TodoCommands,
    private val itemCommands: TodoItemCommands,
) {
    fun findById(opCtx: OperationContext, id: UUID): Todo? = queries.findById(opCtx, id)
    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> = queries.findByCursor(opCtx, input)
    fun findByIds(opCtx: OperationContext, ids: List<UUID>): List<Todo> = queries.findByIds(opCtx, ids)
    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): UUID = commands.create(opCtx, input)
    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput): Boolean = commands.update(opCtx, input)
    fun deleteTodo(opCtx: OperationContext, id: UUID): Boolean = commands.delete(opCtx, id)
    fun batchDeleteTodos(opCtx: OperationContext, ids: List<UUID>): Int = commands.batchDelete(opCtx, ids)
    fun updateItems(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput) =
        itemCommands.update(opCtx, input)
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>): List<TodoItem> =
        queries.findItemsByTodoIds(opCtx, todoIds)
}

@Component
class TodoQueries(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    factory: CrudServiceOpsFactory,
) {
    private val ops: CrudServiceOps<Todo> = factory.create(Todo::class.java, "todo") { it.id }
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl)
    fun findById(opCtx: OperationContext, id: UUID): Todo? = ops.findById(svc(opCtx), id, repo::findById)
    fun findByIds(opCtx: OperationContext, ids: List<UUID>): List<Todo> = ops.findByIds(svc(opCtx), ids, repo::findByIds)
    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> = ops.findByCursor(svc(opCtx), input.cursor, input.limit, repo::findByCursor)
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>): List<TodoItem> = itemRepo.findByTodoIds(svc(opCtx), todoIds)
}

@Component
class TodoCommands(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    private val tx: TxRunner,
    factory: CrudServiceOpsFactory,
) {
    private val ops: CrudServiceOps<Todo> = factory.create(Todo::class.java, "todo") { it.id }
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl)

    fun create(opCtx: OperationContext, input: CreateTodoInput): UUID = tx.withTx(svc(opCtx)) { ctx ->
        val appId = ctx.appId!!
        val now = Instant.now()
        val id = UuidV7.generate()
        repo.insert(ctx, Todo(id = id, appId = appId, installId = ctx.installId, userId = ctx.userId,
            title = input.title, done = input.done ?: false, createdAt = now))
        input.items?.takeIf { it.isNotEmpty() }?.let { items ->
            itemRepo.batchInsert(ctx, items.map { i ->
                TodoItem(id = UuidV7.generate(), appId = appId, todoId = id,
                    content = i.content, done = i.done ?: false, createdAt = now)
            })
        }
        id
    }

    fun update(opCtx: OperationContext, input: UpdateTodoInput): Boolean = tx.withTx(svc(opCtx)) { ctx ->
        val appId = ctx.appId!!
        if (!repo.exists(ctx, appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        repo.partialUpdate(ctx, appId, input)
        ops.evict(ctx, input.id)
        true
    }

    fun delete(opCtx: OperationContext, id: UUID): Boolean = tx.withTx(svc(opCtx)) { ctx ->
        ops.deleteById(ctx, id, repo::deleteById)
    }

    fun batchDelete(opCtx: OperationContext, ids: List<UUID>): Int = tx.withTx(svc(opCtx)) { ctx ->
        ops.deleteByIds(ctx, ids, repo::deleteByIds)
    }
}

@Component
class TodoItemCommands(
    private val repo: TodoItemRepository,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl)

    fun update(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput) = tx.withTx(svc(opCtx)) { ctx ->
        val appId = ctx.appId!!
        val now = Instant.now()
        input.create?.takeIf { it.isNotEmpty() }?.let { creates ->
            repo.batchInsert(ctx, creates.map { c ->
                TodoItem(id = UuidV7.generate(), appId = appId, todoId = c.todoId,
                    content = c.content, done = c.done ?: false, createdAt = now)
            })
        }
        input.update?.takeIf { it.isNotEmpty() }?.let { updates ->
            repo.batchUpdate(ctx, appId, updates)
        }
        input.delete?.takeIf { it.isNotEmpty() }?.let { ids ->
            repo.deleteByIds(ctx, appId, ids)
        }
    }
}

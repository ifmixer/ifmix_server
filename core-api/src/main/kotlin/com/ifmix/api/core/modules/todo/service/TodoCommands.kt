package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.model.todo.Todo
import com.ifmix.api.core.model.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoCommands(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    private val tx: TxRunner,
) {
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
        true
    }

    fun delete(opCtx: OperationContext, id: UUID): Boolean = tx.withTx(svc(opCtx)) { ctx ->
        val appId = ctx.appId!!
        repo.deleteById(ctx, appId, id)
    }

    fun batchDelete(opCtx: OperationContext, ids: List<UUID>): Int = tx.withTx(svc(opCtx)) { ctx ->
        val appId = ctx.appId!!
        ids.map { repo.deleteById(ctx, appId, it) }
        ids.size
    }
}

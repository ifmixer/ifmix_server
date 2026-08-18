package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.model.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoItemCommands(
    private val repo: TodoItemRepository,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl)

    fun update(opCtx: OperationContext, input: UpdateTodoItemsMutationInput) = tx.withTx(svc(opCtx)) { ctx ->
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

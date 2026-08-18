package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.model.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class TodoItemService(
    private val repo: TodoItemRepository,
    private val tx: TxRunner,
) {

    fun findByTodoIds(ctx: OperationContext, todoIds: Collection<UUID>): List<TodoItem> =
        repo.findByTodoIds(ctx.repoCtx, todoIds)

    fun updateItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput): Unit = tx.withTx(ctx) { txCtx ->
        val appId = txCtx.mustGetAppId()
        val now = Instant.now()

        input.create?.takeIf { it.isNotEmpty() }?.let { creates ->
            repo.batchInsert(txCtx.repoCtx, creates.map { c ->
                TodoItem(
                    id = UuidV7.generate(), appId = appId, todoId = c.todoId,
                    content = c.content, done = c.done ?: false,
                    createdAt = now,
                )
            })
        }

        input.update?.takeIf { it.isNotEmpty() }?.let { updates ->
            repo.batchUpdate(txCtx.repoCtx, appId, updates)
        }

        input.delete?.takeIf { it.isNotEmpty() }?.let { ids ->
            repo.deleteByIds(txCtx.repoCtx, appId, ids)
        }
    }
}

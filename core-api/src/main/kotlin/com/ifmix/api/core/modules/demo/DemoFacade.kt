package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.FindOptions
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.demo.handler.TodoAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DemoFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: TodoAggHandler,
) {
    // --- Queries (no tx) ---

    fun findById(ctx: OperationContext, id: UUID): Todo? =
        handler.findById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<Todo> =
        handler.findByIds(mcFactory.forApp(ctx), ctx.mustGetAppId(), ids)

    fun findTodos(ctx: OperationContext, options: FindOptions?): Page<Todo> =
        handler.findTodos(mcFactory.forApp(ctx), ctx.mustGetAppId(), options)

    fun findItemsByTodoIds(ctx: OperationContext, todoIds: Collection<UUID>): List<com.ifmix.api.core.entity.demo.TodoItem> =
        handler.findItemsByTodoIds(mcFactory.forApp(ctx), ctx.mustGetAppId(), todoIds)

    fun countItemsByTodoIds(ctx: OperationContext, todoIds: Collection<UUID>) =
        handler.countItemsByTodoIds(mcFactory.forApp(ctx), ctx.mustGetAppId(), todoIds)

    // --- Mutations (no tx — managed by DataFetcher via GlobalTxRunner) ---

    fun create(ctx: OperationContext, input: CreateTodoInput): Todo =
        handler.create(mcFactory.forApp(ctx), input)

    fun partialUpdate(ctx: OperationContext, input: UpdateTodoInput) {
        handler.partialUpdate(mcFactory.forApp(ctx), ctx.mustGetAppId(), input)
    }

    fun batchUpdateItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput) {
        handler.batchUpdateItems(mcFactory.forApp(ctx), ctx.mustGetAppId(), input)
    }

    fun deleteById(ctx: OperationContext, id: UUID): Boolean =
        handler.deleteById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun deleteByIds(ctx: OperationContext, ids: List<UUID>): Int =
        handler.deleteByIds(mcFactory.forApp(ctx), ctx.mustGetAppId(), ids)
}

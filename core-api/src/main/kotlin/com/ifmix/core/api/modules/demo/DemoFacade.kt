package com.ifmix.core.api.modules.demo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.generated.types.CreateTodoInput
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.UpdateTodoInput
import com.ifmix.core.api.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.demo.handler.TodoAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DemoFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: TodoAggHandler,
) {
    // --- Queries (no tx) ---

    fun findById(ctx: OperationContext, id: UUID): Todo? =
        handler.findById(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<Todo> =
        handler.findByIds(mcFactory.forProject(ctx), ctx.mustGetProjectId(), ids)

    fun findTodos(ctx: OperationContext, findOptions: CommonFindOptions?): Page<Todo> =
        handler.findTodos(mcFactory.forProject(ctx), ctx.mustGetProjectId(), findOptions)

    fun findItemsByTodoIds(ctx: OperationContext, todoIds: Collection<UUID>): List<com.ifmix.core.api.entity.demo.TodoItem> =
        handler.findItemsByTodoIds(mcFactory.forProject(ctx), ctx.mustGetProjectId(), todoIds)

    fun countItemsByTodoIds(ctx: OperationContext, todoIds: Collection<UUID>) =
        handler.countItemsByTodoIds(mcFactory.forProject(ctx), ctx.mustGetProjectId(), todoIds)

    // --- Mutations (no tx — managed by DataFetcher via GlobalTxRunner) ---

    fun create(ctx: OperationContext, input: CreateTodoInput): Todo =
        handler.create(mcFactory.forProject(ctx), input)

    fun partialUpdate(ctx: OperationContext, input: UpdateTodoInput) {
        handler.partialUpdate(mcFactory.forProject(ctx), ctx.mustGetProjectId(), input)
    }

    fun batchUpdateItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput) {
        handler.batchUpdateItems(mcFactory.forProject(ctx), ctx.mustGetProjectId(), input)
    }

    fun deleteById(ctx: OperationContext, id: UUID): Boolean =
        handler.deleteById(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)

    fun deleteByIds(ctx: OperationContext, ids: List<UUID>): Int =
        handler.deleteByIds(mcFactory.forProject(ctx), ctx.mustGetProjectId(), ids)
}

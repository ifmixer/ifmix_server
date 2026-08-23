package com.ifmix.api.core.bff.graphql.customer.demo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.tx.GlobalTxRunner
import com.ifmix.api.core.modules.demo.DemoFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID

@DgsComponent
class DemoFetcher(
    private val demoService: DemoFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsQuery(field = "q_demo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): com.ifmix.api.core.entity.demo.Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = demoService.findById(ctx, id)
            ?: throw IllegalArgumentException("Todo not found: $id")
        return todo
    }

    @DgsQuery(field = "q_demo_findTodosByIds")
    fun findByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): List<com.ifmix.api.core.entity.demo.Todo> {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findByIds(ctx, ids)
    }

    @DgsQuery(field = "q_demo_findTodos")
    fun findTodos(
        dfe: DgsDataFetchingEnvironment,
        @InputArgument findOptions: com.ifmix.api.core.generated.types.CommonFindOptions?,
    ): Page<com.ifmix.api.core.entity.demo.Todo> {
        val ctx = ctxProvider.fromDfe(dfe)
        val page = demoService.findTodos(ctx, findOptions)
        return Page(items = page.items, nextCursor = page.nextCursor, hasMore = page.hasMore)
    }

    @DgsMutation(field = "m_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = globalTx.withTx(ctx) { txCtx -> demoService.create(txCtx, input) }
        return CreateTodoResult(todo = todo)
    }

    @DgsMutation(field = "m_demo_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoResult {
        val ctx = ctxProvider.fromDfe(dfe)
        globalTx.withTx(ctx) { txCtx -> demoService.partialUpdate(txCtx, input) }
        val todo = if (dfe.selectionSet.fields.any { it.name == "todo" }) demoService.findById(ctx, input.id) else null
        return UpdateTodoResult(success = true, todo = todo)
    }

    @DgsMutation(field = "m_demo_batchUpdateTodoItems")
    fun batchUpdateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsResult {
        val ctx = ctxProvider.fromDfe(dfe)
        globalTx.withTx(ctx) { txCtx -> demoService.batchUpdateItems(txCtx, input) }
        return UpdateTodoItemsResult(success = true)
    }

    @DgsMutation(field = "m_demo_deleteTodo")
    fun deleteTodo(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): com.ifmix.api.core.dto.common.OperationResult {
        val ctx = ctxProvider.fromDfe(dfe)
        globalTx.withTx(ctx) { txCtx -> demoService.deleteById(txCtx, id) }
        return com.ifmix.api.core.dto.common.OperationResult(success = true)
    }

    @DgsMutation(field = "m_demo_deleteTodoByIds")
    fun deleteTodoByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): com.ifmix.api.core.dto.common.OperationResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val count = globalTx.withTx(ctx) { txCtx -> demoService.deleteByIds(txCtx, ids) }
        return com.ifmix.api.core.dto.common.OperationResult(success = true, modifiedCount = count)
    }

    private fun tryParseUuid(s: String): UUID? =
        runCatching { UUID.fromString(s) }.getOrNull()
}

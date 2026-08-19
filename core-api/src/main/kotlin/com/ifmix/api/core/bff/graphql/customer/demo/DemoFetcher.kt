package com.ifmix.api.core.bff.graphql.customer.demo

import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.modules.demo.service.DemoModuleService
import com.ifmix.api.core.dto.common.Page
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID

/**
 * Demo 模块 DataFetcher — 使用 MyBatis 访问核心数据。
 */
@DgsComponent
class DemoFetcher(
    private val ctxProvider: OperationContextProvider,
    private val demoService: DemoModuleService,
) {

    @DgsQuery(field = "query_demo_findTodoById")
    fun findTodoById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): com.ifmix.api.core.entity.demo.Todo =
        demoService.findById(ctxProvider.fromDfe(dfe), id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "Todo not found: $id")

    @DgsQuery(field = "query_demo_findTodosByCursor")
    fun findTodosByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): Page<com.ifmix.api.core.entity.demo.Todo> {
        val opCtx = ctxProvider.fromDfe(dfe)
        val queryInput = input ?: TodoQueryInput()
        return demoService.findTodosByCursor(opCtx, queryInput)
    }

    @DgsQuery(field = "query_demo_findTodosByIds")
    fun findTodosByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): List<com.ifmix.api.core.entity.demo.Todo> =
        demoService.findTodosByIds(ctxProvider.fromDfe(dfe), ids)

    @DgsMutation(field = "mutation_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        val todo = demoService.createTodo(opCtx, input)
        return CreateTodoPayload(todo = todo)
    }

    @DgsMutation(field = "mutation_demo_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        return demoService.updateTodo(opCtx, input)
    }

    @DgsMutation(field = "mutation_demo_deleteTodo")
    fun deleteTodo(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteTodoPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        return demoService.deleteTodo(opCtx, id)
    }

    @DgsMutation(field = "mutation_demo_batchDeleteTodos")
    fun batchDeleteTodos(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): DeleteTodoPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        return demoService.batchDeleteTodos(opCtx, ids)
    }

    @DgsMutation(field = "mutation_demo_batchUpdateTodoItems")
    fun batchUpdateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        return demoService.batchUpdateTodoItems(opCtx, input)
    }
}

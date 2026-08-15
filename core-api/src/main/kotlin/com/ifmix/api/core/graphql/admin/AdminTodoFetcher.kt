package com.ifmix.api.core.graphql.admin

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.OperationResult
import com.ifmix.api.core.modules.todo.UpdateTodoRequest
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.service.TodoItemService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

@DgsComponent
class AdminTodoFetcher(
    private val todoService: TodoService,
    private val todoItemService: TodoItemService,
) {

    @DgsMutation
    fun batchDeleteTodos(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val count = todoService.deleteByIds(ctx.requestContext, ids)
        return OperationResult(success = count == ids.size, modifiedCount = count)
    }

    @DgsMutation
    fun batchUpdateTodos(
        @InputArgument patches: List<Map<String, Any?>>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val patchPairs = patches.map { patch ->
            val id = patch["id"] as String
            val req = UpdateTodoRequest(
                title = patch["title"] as? String,
                done = patch["done"] as? Boolean,
            )
            id to req
        }
        val count = todoService.updateByIds(ctx.requestContext, patchPairs)
        return OperationResult(success = count == patchPairs.size, modifiedCount = count)
    }

    @DgsMutation
    fun batchDeleteTodoItems(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val count = todoItemService.deleteByIds(ctx.requestContext, ids)
        return OperationResult(success = true, modifiedCount = count)
    }
}

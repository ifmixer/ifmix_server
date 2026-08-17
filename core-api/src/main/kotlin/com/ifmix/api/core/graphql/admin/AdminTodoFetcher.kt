package com.ifmix.api.core.graphql.admin

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.OperationResult
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

    @DgsMutation(field = "todo_batchDelete")
    fun batchDeleteTodos(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        // 先删除关联 items，再删除 todos
        ids.forEach { id -> todoItemService.deleteByTodoId(ctx, id) }
        val count = todoService.deleteByIds(ctx, ids)
        return OperationResult(success = count == ids.size, modifiedCount = count)
    }

    @DgsMutation(field = "todo_batchUpdate")
    fun batchUpdateTodos(
        @InputArgument patches: List<Map<String, Any?>>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        val patchPairs = patches.map { patch ->
            val id = patch["id"] as String
            val patchMap = mutableMapOf<String, Any?>()
            patch["title"]?.let { patchMap["title"] = it }
            patch["done"]?.let { patchMap["done"] = it }
            patch["meta"]?.let { patchMap["meta"] = it }
            id to patchMap
        }
        val count = todoService.updateByIds(ctx, patchPairs)
        return OperationResult(success = count == patchPairs.size, modifiedCount = count)
    }

    @DgsMutation(field = "todoItem_batchDelete")
    fun batchDeleteTodoItems(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        val count = todoItemService.deleteByIds(ctx, ids)
        return OperationResult(success = true, modifiedCount = count)
    }
}

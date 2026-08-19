package com.ifmix.api.core.graphql.admin

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.OperationResult
import com.ifmix.api.core.modules.demo.DemoFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

@DgsComponent
class AdminDemoFetcher(
    private val demoFacade: DemoFacade,
) {

    @DgsMutation(field = "mutation_demo_batchDeleteTodos")
    fun batchDeleteTodos(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        val count = demoFacade.batchDeleteTodos(ctx, ids)
        return OperationResult(success = count == ids.size, modifiedCount = count)
    }

    @DgsMutation(field = "mutation_demo_batchUpdateTodos")
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
        val count = demoFacade.batchUpdateTodos(ctx, patchPairs)
        return OperationResult(success = count == patchPairs.size, modifiedCount = count)
    }

    @DgsMutation(field = "mutation_demo_batchDeleteTodoItems")
    fun batchDeleteTodoItems(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        val count = demoFacade.batchDeleteTodoItems(ctx, ids)
        return OperationResult(success = true, modifiedCount = count)
    }
}

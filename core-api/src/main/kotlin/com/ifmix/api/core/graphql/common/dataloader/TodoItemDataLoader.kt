package com.ifmix.api.core.graphql.common.dataloader

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.generated.types.TodoItem
import com.ifmix.api.core.modules.todo.mapper.toTodoItem
import com.ifmix.api.core.modules.todo.service.TodoItemService
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.context.DgsContext
import org.dataloader.BatchLoaderEnvironment
import org.dataloader.MappedBatchLoaderWithContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * DataLoader：按 todoId 批量加载 TodoItems。
 * DGS 自动为每个请求创建独立实例（per-request scope）。
 */
@DgsDataLoader(name = "todoItems")
class TodoItemDataLoader(
    private val todoItemService: TodoItemService,
) : MappedBatchLoaderWithContext<String, List<TodoItem>> {

    override fun load(
        keys: Set<String>,
        environment: BatchLoaderEnvironment,
    ): CompletionStage<Map<String, List<TodoItem>>> {
        return CompletableFuture.supplyAsync {
            // 从 DGS context 获取 GraphQLRequestContext（含 appId 等租户信息）
            @Suppress("UNCHECKED_CAST")
            val customContext = DgsContext.getCustomContext<GraphQLRequestContext>(environment)
            val ctx = customContext.requestContext

            todoItemService.findByTodoIds(ctx, keys.toList())
                .map { it.toTodoItem() }
                .groupBy { it.todoId }
        }
    }
}

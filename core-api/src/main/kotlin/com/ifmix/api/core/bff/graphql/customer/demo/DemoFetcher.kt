package com.ifmix.api.core.bff.graphql.customer.demo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.TodoMetrics
import com.ifmix.api.core.entity.demo.TodoWithStats
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.CreateTodoPayload
import com.ifmix.api.core.generated.types.DeleteTodoPayload
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsPayload
import com.ifmix.api.core.generated.types.UpdateTodoPayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.demo.service.DemoModuleService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import org.dataloader.MappedBatchLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@DgsComponent
class DemoFetcher(
    private val demoService: DemoModuleService,
    private val ctxProvider: OperationContextProvider,
) {
    // ===== Query =====

    @DgsQuery(field = "query_demo_findTodoById")
    fun findTodoById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findTodoById(ctx, id)
    }

    @DgsQuery(field = "query_demo_findTodosByCursor")
    fun findTodosByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): Page<Todo> {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findTodosByCursor(ctx, input?.cursor, input?.limit)
    }

    @DgsQuery(field = "query_demo_findTodosByIds")
    fun findTodosByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): List<Todo> {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findTodosByIds(ctx, ids)
    }

    @DgsQuery(field = "query_demo_findTodosWithStats")
    fun findTodosWithStats(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): Page<TodoWithStats> {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findTodosWithStats(ctx, input?.cursor, input?.limit)
    }

    // ===== Mutation =====

    @DgsMutation(field = "mutation_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = demoService.createTodo(ctx, input)
        return CreateTodoPayload(todo = todo)
    }

    @DgsMutation(field = "mutation_demo_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = demoService.updateTodo(ctx, input)
        return UpdateTodoPayload(success = true, todo = todo)
    }

    @DgsMutation(field = "mutation_demo_batchUpdateTodoItems")
    fun batchUpdateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.batchUpdateTodoItems(ctx, input.create, input.update, input.delete)
        return UpdateTodoItemsPayload(success = true)
    }

    @DgsMutation(field = "mutation_demo_deleteTodo")
    fun deleteTodo(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.deleteTodo(ctx, id)
        return DeleteTodoPayload(success = true)
    }

    @DgsMutation(field = "mutation_demo_batchDeleteTodos")
    fun batchDeleteTodos(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.batchDeleteTodos(ctx, ids)
        return DeleteTodoPayload(success = true)
    }

    // ===== Field Resolver: Todo.items via DataLoader =====

    @DgsData(parentType = "Todo", field = "items")
    fun todoItems(dfe: DgsDataFetchingEnvironment): CompletableFuture<List<TodoItem>> {
        val todo = dfe.getSource<Todo>()!!
        val dataLoader = dfe.getDataLoader<UUID, List<TodoItem>>(TodoItemsDataLoader.NAME)!!
        return dataLoader.load(todo.id)
    }

    // ===== Field Resolver: Todo.metrics via DataLoader =====

    @DgsData(parentType = "Todo", field = "metrics")
    fun todoMetrics(dfe: DgsDataFetchingEnvironment): CompletableFuture<TodoMetrics> {
        val todo = dfe.getSource<Todo>()!!
        val dataLoader = dfe.getDataLoader<UUID, TodoMetrics>(TodoMetricsDataLoader.NAME)!!
        return dataLoader.load(todo.id)
    }

    // ===== Field Resolver: 独立字段演示（共享同一个 DataLoader） =====
    // 无论客户端请求了 itemCount / pendingCount / finishCount 中的哪几个，
    // DataLoader 都只触发一次 SQL（DGS 在同一 dispatch cycle 内去重）。

    @DgsData(parentType = "Todo", field = "itemCount")
    fun todoItemCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> {
        val todo = dfe.getSource<Todo>()!!
        return dfe.getDataLoader<UUID, TodoMetrics>(TodoMetricsDataLoader.NAME)!!
            .load(todo.id)
            .thenApply { it.itemCount }
    }

    @DgsData(parentType = "Todo", field = "pendingCount")
    fun todoPendingCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> {
        val todo = dfe.getSource<Todo>()!!
        return dfe.getDataLoader<UUID, TodoMetrics>(TodoMetricsDataLoader.NAME)!!
            .load(todo.id)
            .thenApply { it.pendingItemCount }
    }

    @DgsData(parentType = "Todo", field = "finishCount")
    fun todoFinishCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> {
        val todo = dfe.getSource<Todo>()!!
        return dfe.getDataLoader<UUID, TodoMetrics>(TodoMetricsDataLoader.NAME)!!
            .load(todo.id)
            .thenApply { it.finishedItemCount }
    }
}

@DgsDataLoader(name = TodoItemsDataLoader.NAME, caching = false)
class TodoItemsDataLoader(
    private val todoItemRepo: com.ifmix.api.core.modules.demo.repo.TodoItemRepository,
) : MappedBatchLoader<UUID, List<TodoItem>> {

    override fun load(todoIds: Set<UUID>): CompletionStage<Map<UUID, List<TodoItem>>> {
        val sc = com.ifmix.api.core.infra.db.SvcCtx.DEFAULT
        val items = todoItemRepo.findByTodoIds(sc, todoIds)
        val grouped = items.groupBy { it.todoId }
        val result = todoIds.associateWith { grouped[it] ?: emptyList() }
        return CompletableFuture.completedFuture(result)
    }

    companion object {
        const val NAME = "todoItems"
    }
}

/**
 * DataLoader 演示：批量聚合 Todo 子项统计。
 *
 * 一个 GraphQL 请求中无论查了多少个 Todo，metrics 字段只触发一次 SQL：
 * SELECT todo_id, COUNT(*), COUNT(*) FILTER(...) FROM core_todo_item WHERE todo_id IN (?) GROUP BY todo_id
 */
@DgsDataLoader(name = TodoMetricsDataLoader.NAME, caching = false)
class TodoMetricsDataLoader(
    private val todoItemRepo: com.ifmix.api.core.modules.demo.repo.TodoItemRepository,
) : MappedBatchLoader<UUID, TodoMetrics> {

    override fun load(todoIds: Set<UUID>): CompletionStage<Map<UUID, TodoMetrics>> {
        val sc = com.ifmix.api.core.infra.db.SvcCtx.DEFAULT
        val metricsMap = todoItemRepo.aggregateMetricsByTodoIds(sc, todoIds)
        // 没有 item 的 todo 返回零值
        val result = todoIds.associateWith { metricsMap[it] ?: TodoMetrics() }
        return CompletableFuture.completedFuture(result)
    }

    companion object {
        const val NAME = "todoMetrics"
    }
}

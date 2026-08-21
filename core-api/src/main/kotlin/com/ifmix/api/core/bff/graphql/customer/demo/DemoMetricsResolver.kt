package com.ifmix.api.core.bff.graphql.customer.demo

import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.generated.types.TodoMetrics
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.jimmer.OperationContextHolder
import com.ifmix.api.core.modules.demo.repo.TodoItemMetricsCounts
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsDataLoader
import org.dataloader.MappedBatchLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Todo.metrics / Todo.pendingCount / Todo.finishCount / Todo.itemCount 的 resolver。
 * 全部通过 DataLoader 批量加载避免 N+1。
 *
 * 使用流程:
 * 1. GraphQL 引擎遍历每个 Todo 对象，调用对应 field 的 resolver
 * 2. resolver 只是把 todoId 注册到 DataLoader（不立即查 DB）
 * 3. DGS 在当前层级所有 field 收集完后，触发 DataLoader.load() 批量查询
 * 4. DataLoader 调用 TodoItemRepository.countByTodoIds() 一次查出所有统计
 */
@DgsComponent
class DemoMetricsResolver {

    // --- 复合字段: 返回 TodoMetrics 对象 ---

    @DgsData(parentType = "Todo", field = "metrics")
    fun metrics(dfe: DgsDataFetchingEnvironment): CompletableFuture<TodoMetrics> {
        val todo = dfe.getSource<Todo>()!!
        val loader = dfe.getDataLoader<UUID, TodoMetrics>(TodoMetricsDataLoader.NAME)!!
        return loader.load(todo.id)
    }

    // --- 独立字段: 各返回单个 Int，演示 DGS DataLoader 解析独立标量字段 ---

    @DgsData(parentType = "Todo", field = "itemCount")
    fun itemCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> {
        return loadMetricField(dfe) { it.itemCount }
    }

    @DgsData(parentType = "Todo", field = "pendingCount")
    fun pendingCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> {
        return loadMetricField(dfe) { it.pendingItemCount }
    }

    @DgsData(parentType = "Todo", field = "finishCount")
    fun finishCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> {
        return loadMetricField(dfe) { it.finishedItemCount }
    }

    /**
     * 复用同一个 DataLoader，加载后提取单个字段。
     * 由于 DataLoader caching=false 但同一请求内同一 id 只 load 一次，
     * 多个字段共享同一批次查询，不会重复。
     */
    private fun loadMetricField(
        dfe: DgsDataFetchingEnvironment,
        extract: (TodoMetrics) -> Int,
    ): CompletableFuture<Int> {
        val todo = dfe.getSource<Todo>()!!
        val loader = dfe.getDataLoader<UUID, TodoMetrics>(TodoMetricsDataLoader.NAME)!!
        return loader.load(todo.id).thenApply(extract)
    }
}

/**
 * DataLoader: 批量按 todoId 查询 item 统计。
 * caching=false — 只 batching，防 mutation 间脏读（项目约定 #12）。
 */
@DgsDataLoader(name = TodoMetricsDataLoader.NAME, caching = false)
class TodoMetricsDataLoader(
    private val todoItemRepo: TodoItemRepository,
    private val mcFactory: ModuleCtxFactory,
) : MappedBatchLoader<UUID, TodoMetrics> {

    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, TodoMetrics>> {
        val opCtx = OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()

        val countsMap = todoItemRepo.countByTodoIds(mc, appId, ids)

        // 对于没有 item 的 todo，返回全零 metrics
        val result = ids.associateWith { todoId ->
            val counts = countsMap[todoId]
            TodoMetrics(
                itemCount = counts?.itemCount ?: 0,
                pendingItemCount = counts?.pendingItemCount ?: 0,
                finishedItemCount = counts?.finishedItemCount ?: 0,
            )
        }
        return CompletableFuture.completedFuture(result)
    }

    companion object {
        const val NAME = "todoMetrics"
    }
}

package com.ifmix.api.core.bff.graphql.customer.demo

import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.infra.jimmer.OperationContextHolder
import com.ifmix.api.core.modules.demo.DemoFacade
import com.ifmix.api.core.modules.demo.repo.TodoItemCounts
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsDataLoader
import org.dataloader.MappedBatchLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@DgsComponent
class TodoItemsResolver {

    @DgsData(parentType = "Todo", field = "items")
    fun items(dfe: DgsDataFetchingEnvironment): CompletableFuture<List<TodoItem>> {
        val todo = dfe.getSource<Todo>()!!
        val loader = dfe.getDataLoader<UUID, List<TodoItem>>(TodoItemsDataLoader.NAME)!!
        return loader.load(todo.id)
    }

    @DgsData(parentType = "Todo", field = "itemCount")
    fun itemCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> =
        loadCount(dfe) { it.itemCount }

    @DgsData(parentType = "Todo", field = "pendingCount")
    fun pendingCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> =
        loadCount(dfe) { it.pendingCount }

    @DgsData(parentType = "Todo", field = "finishCount")
    fun finishCount(dfe: DgsDataFetchingEnvironment): CompletableFuture<Int> =
        loadCount(dfe) { it.finishCount }

    private fun loadCount(dfe: DgsDataFetchingEnvironment, extract: (TodoItemCounts) -> Int): CompletableFuture<Int> {
        val todo = dfe.getSource<Todo>()!!
        val loader = dfe.getDataLoader<UUID, TodoItemCounts>(TodoItemCountsDataLoader.NAME)!!
        return loader.load(todo.id).thenApply(extract)
    }
}

@DgsDataLoader(name = TodoItemsDataLoader.NAME, caching = false)
class TodoItemsDataLoader(
    private val demoFacade: DemoFacade,
) : MappedBatchLoader<UUID, List<TodoItem>> {

    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, List<TodoItem>>> {
        val opCtx = OperationContextHolder.current()
        val allItems = demoFacade.findItemsByTodoIds(opCtx, ids)
        val grouped = allItems.groupBy { it.todoId }
        val result = ids.associateWith { grouped[it] ?: emptyList() }
        return CompletableFuture.completedFuture(result)
    }

    companion object {
        const val NAME = "todoItems"
    }
}

@DgsDataLoader(name = TodoItemCountsDataLoader.NAME, caching = false)
class TodoItemCountsDataLoader(
    private val demoFacade: DemoFacade,
) : MappedBatchLoader<UUID, TodoItemCounts> {

    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, TodoItemCounts>> {
        val opCtx = OperationContextHolder.current()
        val countsMap = demoFacade.countItemsByTodoIds(opCtx, ids)
        val result = ids.associateWith { countsMap[it] ?: ZERO }
        return CompletableFuture.completedFuture(result)
    }

    companion object {
        const val NAME = "todoItemCounts"
        private val ZERO = TodoItemCounts(itemCount = 0, pendingCount = 0, finishCount = 0)
    }
}

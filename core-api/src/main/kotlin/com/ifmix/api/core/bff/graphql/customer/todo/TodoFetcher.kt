package com.ifmix.api.core.bff.graphql.customer.todo

import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.demo.DemoFacade
import com.ifmix.api.core.modules.demo.handler.TodoHandler
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID

/**
 * Todo 演示模块 GraphQL DataFetcher。
 */
@DgsComponent
class TodoFetcher(
    private val demoService: DemoFacade,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsQuery(field = "query_demo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = demoService.findById(ctx, id)
            ?: throw IllegalArgumentException("Todo not found: $id")
        return todo.toDto()
    }

    @DgsQuery(field = "query_demo_findTodosByIds")
    fun findByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): List<Todo> {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findByIds(ctx, ids).map { it.toDto() }
    }

    @DgsQuery(field = "query_demo_findTodosByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): TodoPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val cursor = input?.cursor?.let { tryParseUuid(it) }
        val limit = (input?.limit ?: 20).coerceIn(1, 100)
        val todos = demoService.findByCursor(ctx, cursor, limit + 1, input?.filter)
        return todos.toPage(limit)
    }

    @DgsQuery(field = "query_demo_findTodos")
    fun findTodos(
        dfe: DgsDataFetchingEnvironment,
        @InputArgument filter: FilterGroup?,
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
    ): TodoPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val parsedCursor = cursor?.let { tryParseUuid(it) }
        val pageSize = (limit ?: 20).coerceIn(1, 100)
        val todos = demoService.findByFilter(ctx, filter, parsedCursor, pageSize + 1)
        return todos.toPage(pageSize)
    }

    @DgsMutation(field = "mutation_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val items = input.items?.map { TodoHandler.CreateItemInput(it.content, it.done, it.note) }
        val todo = demoService.create(ctx, input.title, input.done, input.note, items)
        return CreateTodoPayload(todo = todo.toDto())
    }

    @DgsMutation(field = "mutation_demo_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.partialUpdate(ctx, input.id, input.set?.title, input.set?.done, input.set?.note)
        val todo = demoService.findById(ctx, input.id)
        return UpdateTodoPayload(success = true, todo = todo?.toDto())
    }

    @DgsMutation(field = "mutation_demo_batchUpdateTodoItems")
    fun batchUpdateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val batchInput = TodoHandler.BatchUpdateItemsInput(
            create = input.create?.map { TodoHandler.CreateItemForTodo(it.todoId, it.content, it.done, it.note) },
            update = input.update?.map { TodoHandler.UpdateItemEntry(it.id, it.set?.content, it.set?.done, it.set?.note) },
            delete = input.delete,
        )
        demoService.batchUpdateItems(ctx, batchInput)
        return UpdateTodoItemsPayload(success = true)
    }

    @DgsMutation(field = "mutation_demo_deleteTodo")
    fun deleteTodo(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.deleteById(ctx, id)
        return DeleteTodoPayload(success = true)
    }

    @DgsMutation(field = "mutation_demo_batchDeleteTodos")
    fun batchDeleteTodos(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.batchDelete(ctx, ids)
        return DeleteTodoPayload(success = true)
    }

    private fun tryParseUuid(s: String): UUID? =
        runCatching { UUID.fromString(s) }.getOrNull()
}

// --- Entity → DTO mappings ---
private fun com.ifmix.api.core.entity.todo.Todo.toDto(): Todo = Todo(
    id = id,
    title = title,
    done = done,
    note = note,
    meta = meta,
    items = emptyList(),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun List<com.ifmix.api.core.entity.todo.Todo>.toPage(limit: Int): TodoPage {
    val hasMore = size > limit
    val items = if (hasMore) dropLast(1) else this
    return TodoPage(
        items = items.map { it.toDto() },
        nextCursor = if (hasMore && items.isNotEmpty()) items.last().id.toString() else null,
        hasMore = hasMore,
    )
}

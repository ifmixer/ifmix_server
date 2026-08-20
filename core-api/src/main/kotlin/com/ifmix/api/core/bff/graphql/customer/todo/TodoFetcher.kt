package com.ifmix.api.core.bff.graphql.customer.todo

import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.demo.DemoFacade
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
        val page = demoService.findByCursor(ctx, cursor, limit, input?.filter)
        return page.toTodoPage()
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
        val page = demoService.findByFilter(ctx, filter, parsedCursor, pageSize)
        return page.toTodoPage()
    }

    @DgsMutation(field = "mutation_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = demoService.create(ctx, input.title, input.done, input.note, input.items)
        return CreateTodoPayload(todo = todo.toDto())
    }

    @DgsMutation(field = "mutation_demo_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.partialUpdate(ctx, input)
        val todo = demoService.findById(ctx, input.id)
        return UpdateTodoPayload(success = true, todo = todo?.toDto())
    }

    @DgsMutation(field = "mutation_demo_batchUpdateTodoItems")
    fun batchUpdateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.batchUpdateItems(ctx, input)
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

private fun com.ifmix.api.core.dto.common.Page<com.ifmix.api.core.entity.todo.Todo>.toTodoPage(): TodoPage =
    TodoPage(
        items = items.map { it.toDto() },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )

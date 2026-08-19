package com.ifmix.api.core.bff.graphql.customer.todo

import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.demo.service.DemoModuleService
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
    private val demoService: DemoModuleService,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsQuery(field = "query_demo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = demoService.findById(ctx, id)
            ?: throw IllegalArgumentException("Todo not found: $id")
        return todo.toDto()
    }

    @DgsQuery(field = "query_demo_findTodosByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): TodoPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val cursor = input?.cursor?.let { tryParseUuid(it) }
        val limit = (input?.limit ?: 20).coerceIn(1, 100)
        val todos = demoService.findByCursor(ctx, cursor, limit)
        return todos.toPage()
    }

    @DgsMutation(field = "mutation_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = demoService.create(ctx, input.title, input.done)
        return CreateTodoPayload(todo = todo.toDto())
    }

    @DgsMutation(field = "mutation_demo_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        demoService.partialUpdate(ctx, input.id, input.set?.title, input.set?.done)
        val todo = demoService.findById(ctx, input.id)
        return UpdateTodoPayload(success = true, todo = todo?.toDto())
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
        ids.forEach { demoService.deleteById(ctx, it) }
        return DeleteTodoPayload(success = true)
    }

    private fun tryParseUuid(s: String): UUID? =
        runCatching { UUID.fromString(s) }.getOrNull()
}

// --- Entity → DTO mappings ---
private fun com.ifmix.api.core.entity.demo.Todo.toDto(): Todo = Todo(
    id = id,
    title = title,
    done = done,
    note = null,
    meta = null,
    items = emptyList(),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun List<com.ifmix.api.core.entity.demo.Todo>.toPage(): TodoPage {
    val hasMore = size > 20
    val items = if (hasMore) dropLast(1) else this
    return TodoPage(
        items = items.map { it.toDto() },
        nextCursor = if (hasMore && items.isNotEmpty()) items.last().id.toString() else null,
        hasMore = hasMore,
    )
}

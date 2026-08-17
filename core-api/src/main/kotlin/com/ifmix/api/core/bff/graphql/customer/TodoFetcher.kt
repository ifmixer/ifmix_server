package com.ifmix.api.core.bff.graphql.customer

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.infra.graphql.FetcherBuilder
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.CreateTodoPayload
import com.ifmix.api.core.generated.types.DeleteTodoPayload
import com.ifmix.api.core.generated.types.TodoPage
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsPayload
import com.ifmix.api.core.generated.types.UpdateTodoPayload
import com.ifmix.api.core.modules.todo.service.TodoService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import org.babyfish.jimmer.sql.fetcher.Fetcher
import java.util.UUID

/**
 * Todo GraphQL DataFetcher。
 *
 * 所有方法先从 dfe 提取 OperationContext，再按选定的业务逻辑委托给 TodoService。
 * Query 方法使用 fetcherBuilder 从 selection set 构建 Jimmer Fetcher 实现按需查询。
 * Mutation 返回 Payload 类型；若客户端 select 了 entity 字段则回查。
 */
@DgsComponent
class TodoFetcher(
    private val todoService: TodoService,
    private val ctxProvider: OperationContextProvider,
    private val fetcherBuilder: FetcherBuilder,
) {

    // ==================== Query ====================

    @DgsQuery(field = "query_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        val fetcher: Fetcher<Todo> = fetcherBuilder.build(Todo::class, dfe.selectionSet)
        return todoService.findById(ctx, id, fetcher)
            ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "query_findTodosByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): TodoPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val fetcher: Fetcher<Todo> = fetcherBuilder.build(Todo::class, dfe.selectionSet)
        val page = todoService.findByCursor(ctx, input ?: TodoQueryInput(), fetcher)
        return TodoPage(
            items = page.items,
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsQuery(field = "query_findTodosByIds")
    fun findByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): List<Todo> {
        val ctx = ctxProvider.fromDfe(dfe)
        val fetcher: Fetcher<Todo> = fetcherBuilder.build(Todo::class, dfe.selectionSet)
        return todoService.findByIds(ctx, ids, fetcher)
    }

    // ==================== Mutation: Create ====================

    @DgsMutation(field = "mutation_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val id = todoService.createTodo(ctx, input)
        // 客户端是否 select 了 todo 字段？回查
        val todo = fetcherBuilder.buildFromField(Todo::class, dfe.selectionSet, "todo")
            ?.let { fetcher -> todoService.findById(ctx, id, fetcher) }
            ?: throw ApiError(ErrorCode.INTERNAL, "Failed to create todo")
        return CreateTodoPayload(todo = todo)
    }

    // ==================== Mutation: Update Todo ====================

    @DgsMutation(field = "mutation_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = todoService.updateTodo(ctx, input)
        // 客户端是否 select 了 todo 字段？回查
        val todo = if (success) {
            fetcherBuilder.buildFromField(Todo::class, dfe.selectionSet, "todo")
                ?.let { fetcher -> todoService.findById(ctx, input.id, fetcher) }
        } else null
        return UpdateTodoPayload(success = success, todo = todo)
    }

    // ==================== Mutation: Update TodoItems ====================

    @DgsMutation(field = "mutation_updateTodoItems")
    fun updateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        todoService.updateTodoItems(ctx, input)
        return UpdateTodoItemsPayload(success = true)
    }

    // ==================== Mutation: Delete ====================

    @DgsMutation(field = "mutation_deleteTodoById")
    fun deleteTodoById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = todoService.deleteTodo(ctx, id)
        return DeleteTodoPayload(success = success)
    }

    @DgsMutation(field = "mutation_deleteTodosByIds")
    fun deleteTodosByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val count = todoService.deleteTodosByIds(ctx, ids)
        return DeleteTodoPayload(success = count == ids.size)
    }

}

package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.ownsRow
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.Bff
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.CreateTodoItemInput
import com.ifmix.api.core.graphql.generated.types.Todo
import com.ifmix.api.core.graphql.generated.types.TodoConnection
import com.ifmix.api.core.graphql.generated.types.TodoItem
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.modules.demo.DemoFacade
import com.ifmix.api.core.modules.demo.toTodo
import com.ifmix.api.core.modules.demo.toTodoItem
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import java.util.concurrent.CompletableFuture

@DgsComponent
class DemoFetcher(
    private val demoFacade: DemoFacade,
) {

    @DgsQuery(field = "query_demo_findTodoById")
    fun findTodoById(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Todo? {
        val ctx = getContext(dfe)
        val doc = demoFacade.findTodoById(ctx, id) ?: return null
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, doc.userId?.toHexString(), doc.installId?.toHexString())) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        return doc.toTodo()
    }

    @DgsQuery(field = "query_demo_listTodos")
    fun listTodos(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        @InputArgument userId: String?,
        dfe: DgsDataFetchingEnvironment,
    ): TodoConnection {
        val ctx = getContext(dfe)
        val page = demoFacade.listTodos(ctx, cursor, limit)
        return TodoConnection(
            items = page.items.map { it.toTodo() },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation(field = "mutation_demo_createTodo")
    fun createTodo(
        @InputArgument input: CreateTodoInput,
        dfe: DgsDataFetchingEnvironment,
    ): Todo {
        val ctx = getContext(dfe)
        val todoId = demoFacade.createTodo(ctx, input)
        return demoFacade.findTodoById(ctx, todoId)?.toTodo()
            ?: throw IllegalStateException("Failed to read created todo")
    }

    @DgsMutation(field = "mutation_demo_updateTodo")
    fun updateTodo(
        @InputArgument id: String,
        @InputArgument input: UpdateTodoInput,
        dfe: DgsDataFetchingEnvironment,
    ): Todo {
        val ctx = getContext(dfe)
        val doc = demoFacade.findTodoById(ctx, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "todo not found")
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, doc.userId?.toHexString(), doc.installId?.toHexString())) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        demoFacade.updateTodo(ctx, id, input)
        return demoFacade.findTodoById(ctx, id)?.toTodo()
            ?: throw IllegalStateException("Failed to read updated todo")
    }

    @DgsMutation(field = "mutation_demo_deleteTodo")
    fun deleteTodo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        val doc = demoFacade.findTodoById(ctx, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "todo not found")
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, doc.userId?.toHexString(), doc.installId?.toHexString())) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        return demoFacade.deleteTodo(ctx, id)
    }

    @DgsMutation(field = "mutation_demo_createTodoItem")
    fun createTodoItem(
        @InputArgument todoId: String,
        @InputArgument input: CreateTodoItemInput,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItem {
        val ctx = getContext(dfe)
        val todo = demoFacade.findTodoById(ctx, todoId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "todo not found")
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, todo.userId?.toHexString(), todo.installId?.toHexString())) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        val itemId = demoFacade.createTodoItem(ctx, todoId, input)
        return demoFacade.findTodoItemById(ctx, itemId)?.toTodoItem()
            ?: throw IllegalStateException("Failed to read created todo item")
    }

    @DgsMutation(field = "mutation_demo_updateTodoItem")
    fun updateTodoItem(
        @InputArgument id: String,
        @InputArgument input: UpdateTodoItemInput,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItem {
        val ctx = getContext(dfe)
        demoFacade.updateTodoItem(ctx, id, input)
        return demoFacade.findTodoItemById(ctx, id)?.toTodoItem()
            ?: throw IllegalStateException("Failed to read updated todo item")
    }

    @DgsMutation(field = "mutation_demo_deleteTodoItem")
    fun deleteTodoItem(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        return demoFacade.deleteTodoItem(ctx, id)
    }

    @DgsData(parentType = "Todo", field = "items")
    fun items(dfe: DgsDataFetchingEnvironment): CompletableFuture<List<TodoItem>> {
        val dataLoader = dfe.getDataLoader<String, List<TodoItem>>("todoItems")
            ?: throw IllegalStateException("todoItems DataLoader not registered")
        val todo = dfe.getSource<Todo>()
            ?: throw IllegalStateException("Expected Todo source but got null")
        return dataLoader.load(todo.id)
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): RequestContext =
        DgsContext.getCustomContext(dfe)
}

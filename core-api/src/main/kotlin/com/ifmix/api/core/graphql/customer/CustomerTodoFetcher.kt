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
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.mapper.toTodo
import com.ifmix.api.core.modules.todo.mapper.toTodoItem
import com.ifmix.api.core.modules.todo.service.TodoItemService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import java.util.concurrent.CompletableFuture

@DgsComponent
class CustomerTodoFetcher(
    private val todoService: TodoService,
    private val todoItemService: TodoItemService,
) {

    @DgsQuery(field = "todo_get")
    fun todo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Todo? {
        val ctx = getContext(dfe)
        val doc = todoService.findById(ctx, id) ?: return null
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        return doc.toTodo()
    }

    @DgsQuery(field = "todo_list")
    fun todos(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        @InputArgument userId: String?,
        dfe: DgsDataFetchingEnvironment,
    ): TodoConnection {
        val ctx = getContext(dfe)
        val page = todoService.findByCursor(ctx, CursorQueryInput(cursor = cursor, limit = limit))
        return TodoConnection(
            items = page.items.map { it.toTodo() },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation(field = "todo_create")
    fun createTodo(
        @InputArgument input: CreateTodoInput,
        dfe: DgsDataFetchingEnvironment,
    ): Todo {
        val ctx = getContext(dfe)
        val todoId = todoService.create(ctx, input)

        input.items?.forEach { itemInput ->
            todoItemService.create(ctx, todoId, itemInput)
        }

        return todoService.getById(ctx, todoId, useCache = false).toTodo()
    }

    @DgsMutation(field = "todo_update")
    fun updateTodo(
        @InputArgument id: String,
        @InputArgument input: UpdateTodoInput,
        dfe: DgsDataFetchingEnvironment,
    ): Todo {
        val ctx = getContext(dfe)
        val doc = todoService.getById(ctx, id)
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }

        // 1. 更新 todo 本身
        todoService.update(ctx, id, input)

        // 2. 处理嵌套 items
        input.items?.forEach { mutation ->
            todoItemService.applyMutation(ctx, todoId = id, mutation)
        }

        return todoService.getById(ctx, id, useCache = false).toTodo()
    }

    @DgsMutation(field = "todo_delete")
    fun deleteTodo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        val doc = todoService.getById(ctx, id)
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        todoItemService.deleteByTodoId(ctx, id)
        return todoService.deleteById(ctx, id)
    }

    @DgsMutation(field = "todoItem_create")
    fun createTodoItem(
        @InputArgument todoId: String,
        @InputArgument input: CreateTodoItemInput,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItem {
        val ctx = getContext(dfe)
        val todo = todoService.getById(ctx, todoId)
        if (ctx.bff == Bff.CUSTOMER && !ownsRow(ctx, todo.userId, todo.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        val id = todoItemService.create(ctx, todoId, input)
        return todoItemService.getById(ctx, id).toTodoItem()
    }

    @DgsMutation(field = "todoItem_update")
    fun updateTodoItem(
        @InputArgument id: String,
        @InputArgument input: UpdateTodoItemInput,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItem {
        val ctx = getContext(dfe)
        todoItemService.update(ctx, id, input)
        return todoItemService.getById(ctx, id).toTodoItem()
    }

    @DgsMutation(field = "todoItem_delete")
    fun deleteTodoItem(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        return todoItemService.deleteById(ctx, id)
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

package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.ownsRow
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.TodoConnection
import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import com.ifmix.api.core.modules.todo.mapper.toTodoItemType
import com.ifmix.api.core.modules.todo.mapper.toTodoType
import com.ifmix.api.core.modules.todo.service.TodoItemService
import com.ifmix.api.core.modules.todo.TodoService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import org.dataloader.DataLoader
import java.util.concurrent.CompletableFuture

@DgsComponent
class CustomerTodoFetcher(
    private val todoService: TodoService,
    private val todoItemService: TodoItemService,
) {

    @DgsQuery
    fun todo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): TodoType? {
        val ctx = getContext(dfe)
        val doc = todoService.findById(ctx.requestContext, id) ?: return null
        if (ctx.bff == "customer" && !ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        return doc.toTodoType()
    }

    @DgsQuery
    fun todos(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        @InputArgument userId: String?,
        dfe: DgsDataFetchingEnvironment,
    ): TodoConnection {
        val ctx = getContext(dfe)
        val input = CursorQueryInput(cursor = cursor, limit = limit)
        val page = todoService.findByCursor(ctx.requestContext, input)
        return TodoConnection(
            items = page.items.map { it.toTodoType() },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation
    fun createTodo(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoType {
        val ctx = getContext(dfe)
        val title = input["title"] as String
        val meta = input["meta"] as? Map<String, Any?>
        val items = input["items"] as? List<Map<String, Any?>>

        val todoId = todoService.create(ctx.requestContext, title, meta)

        // 创建关联 items
        items?.forEach { item ->
            val content = item["content"] as String
            val done = item["done"] as? Boolean ?: false
            todoItemService.create(ctx.requestContext, todoId, content, done)
        }

        return todoService.getById(ctx.requestContext, todoId).toTodoType()
    }

    @DgsMutation
    fun updateTodo(
        @InputArgument id: String,
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoType {
        val ctx = getContext(dfe)
        val doc = todoService.getById(ctx.requestContext, id)
        if (ctx.bff == "customer" && !ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        todoService.update(
            ctx.requestContext, id,
            title = input["title"] as? String,
            done = input["done"] as? Boolean,
            meta = input["meta"] as? Map<String, Any?>,
        )
        return todoService.getById(ctx.requestContext, id).toTodoType()
    }

    @DgsMutation
    fun deleteTodo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        val doc = todoService.getById(ctx.requestContext, id)
        if (ctx.bff == "customer" && !ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        todoItemService.deleteByTodoId(ctx.requestContext, id)
        return todoService.deleteById(ctx.requestContext, id)
    }

    @DgsMutation
    fun createTodoItem(
        @InputArgument todoId: String,
        @InputArgument input: Map<String, Any>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItemType {
        val ctx = getContext(dfe)
        val todo = todoService.getById(ctx.requestContext, todoId)
        if (ctx.bff == "customer" && !ownsRow(ctx.requestContext, todo.userId, todo.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        val content = input["content"] as String
        val done = input["done"] as? Boolean ?: false
        val id = todoItemService.create(ctx.requestContext, todoId, content, done)
        return todoItemService.getById(ctx.requestContext, id).toTodoItemType()
    }

    @DgsMutation
    fun updateTodoItem(
        @InputArgument id: String,
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItemType {
        val ctx = getContext(dfe)
        val content = input["content"] as? String
        val done = input["done"] as? Boolean
        todoItemService.update(ctx.requestContext, id, content, done)
        return todoItemService.getById(ctx.requestContext, id).toTodoItemType()
    }

    @DgsMutation
    fun deleteTodoItem(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        return todoItemService.deleteById(ctx.requestContext, id)
    }

    @DgsData(parentType = "Todo", field = "items")
    fun items(dfe: DgsDataFetchingEnvironment): CompletableFuture<List<TodoItemType>> {
        @Suppress("UNCHECKED_CAST")
        val dataLoader = dfe.getDataLoader<String, List<TodoItemType>>("todoItems")
            ?: throw IllegalStateException("todoItems DataLoader not registered")
        val todo = dfe.getSource<TodoType>()
            ?: throw IllegalStateException("Expected TodoType source but got null")
        return dataLoader.load(todo.id)
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): GraphQLRequestContext =
        DgsContext.getCustomContext(dfe)
}

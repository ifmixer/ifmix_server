package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.handler.TodoEntityHandler
import com.ifmix.api.core.modules.demo.handler.TodoItemEntityHandler
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.CreateTodoItemInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.modules.demo.entity.toTodo
import com.ifmix.api.core.modules.demo.entity.toTodoItem
import org.bson.types.ObjectId

/**
 * Demo 模块门面，编排 Todo 和 TodoItem 业务操作。
 */
class DemoFacade(
    private val todoHandler: TodoEntityHandler,
    private val todoItemHandler: TodoItemEntityHandler,
) {

    // ---- Query ----

    fun findTodoById(ctx: RequestContext, id: String): TodoEntity? =
        todoHandler.findByIdWithCtx(ctx.appId, id)

    fun findTodosByIds(ctx: RequestContext, ids: List<String>): List<TodoEntity> =
        todoHandler.findByIds(ctx.appId, ids)

    fun listTodos(ctx: RequestContext, cursor: String?, limit: Int?): com.ifmix.api.core.common.db.Page<TodoEntity> {
        val input = com.ifmix.api.core.common.db.CursorQueryInput(cursor = cursor, limit = limit)
        return todoHandler.findByCursor(ctx.appId, input)
    }

    // ---- Mutation: todo ----

    fun createTodo(ctx: RequestContext, input: CreateTodoInput): String {
        val todoId = todoHandler.create(ctx.appId, input)
        input.items?.forEach { itemInput ->
            todoItemHandler.create(ctx.appId, todoId, itemInput)
        }
        return todoId
    }

    fun updateTodo(ctx: RequestContext, id: String, input: UpdateTodoInput): Boolean {
        val result = todoHandler.update(ctx.appId, id, input)
        input.items?.forEach { mutation ->
            todoItemHandler.applyMutation(ctx.appId, id, mutation)
        }
        return result
    }

    fun deleteTodo(ctx: RequestContext, id: String): Boolean {
        todoItemHandler.softDeleteByTodoId(ctx.appId, id)
        return todoHandler.delete(ctx.appId, id)
    }

    // ---- Mutation: todoItem ----

    fun createTodoItem(ctx: RequestContext, todoId: String, input: CreateTodoItemInput): String {
        return todoItemHandler.create(ctx.appId, todoId, input)
    }

    fun findTodoItemById(ctx: RequestContext, id: String): TodoItemEntity? =
        todoItemHandler.getById(ctx.appId, id)

    fun updateTodoItem(ctx: RequestContext, id: String, input: UpdateTodoItemInput): Boolean {
        return todoItemHandler.update(ctx.appId, id, input)
    }

    fun deleteTodoItem(ctx: RequestContext, id: String): Boolean {
        return todoItemHandler.softDeleteById(ctx.appId, id)
    }

    // ---- Admin batch ----

    fun batchDeleteTodos(ctx: RequestContext, ids: List<String>): Int {
        ids.forEach { id -> todoItemHandler.softDeleteByTodoId(ctx.appId, id) }
        return todoHandler.deleteByIds(ctx.appId, ids)
    }

    fun batchUpdateTodos(ctx: RequestContext, patches: List<Pair<String, Map<String, Any?>>>): Int =
        todoHandler.updateByIds(ctx.appId, patches.toMap())

    fun batchDeleteTodoItems(ctx: RequestContext, ids: List<String>): Int =
        todoItemHandler.softDeleteByIds(ctx.appId, ids)
}

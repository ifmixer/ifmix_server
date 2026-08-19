package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.CreateTodoItemInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.handler.TodoEntityHandler
import com.ifmix.api.core.modules.demo.handler.TodoItemEntityHandler
import com.ifmix.api.core.modules.demo.toTodo
import com.ifmix.api.core.modules.demo.toTodoItem
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
        todoHandler.findByIdWithCtx(ObjectId(ctx.appId), ObjectId(id))

    fun findTodosByIds(ctx: RequestContext, ids: List<String>): List<TodoEntity> {
        val appId = ObjectId(ctx.appId)
        return todoHandler.findByIds(appId, ids.map { ObjectId(it) })
    }

    fun listTodos(ctx: RequestContext, cursor: String?, limit: Int?): Page<TodoEntity> =
        todoHandler.findByCursor(ObjectId(ctx.appId), CursorQueryInput(cursor = cursor, limit = limit))

    // ---- Mutation: todo ----

    fun createTodo(ctx: RequestContext, input: CreateTodoInput): String {
        val appId = ObjectId(ctx.appId)
        val todoId = todoHandler.create(appId, input).toHexString()
        input.items?.forEach { itemInput ->
            todoItemHandler.create(appId, ObjectId(todoId), itemInput)
        }
        return todoId
    }

    fun updateTodo(ctx: RequestContext, id: String, input: UpdateTodoInput): Boolean {
        val appId = ObjectId(ctx.appId)
        val result = todoHandler.update(appId, ObjectId(id), input)
        input.items?.forEach { mutation ->
            todoItemHandler.applyMutation(appId, ObjectId(id), mutation)
        }
        return result
    }

    fun deleteTodo(ctx: RequestContext, id: String): Boolean {
        val appId = ObjectId(ctx.appId)
        val objectId = ObjectId(id)
        todoItemHandler.softDeleteByTodoId(appId, objectId)
        return todoHandler.delete(appId, objectId)
    }

    // ---- Mutation: todoItem ----

    fun createTodoItem(ctx: RequestContext, todoId: String, input: CreateTodoItemInput): String {
        val appId = ObjectId(ctx.appId)
        return todoItemHandler.create(appId, ObjectId(todoId), input).toHexString()
    }

    fun findTodoItemById(ctx: RequestContext, id: String): TodoItemEntity? =
        todoItemHandler.getById(ObjectId(ctx.appId), ObjectId(id))

    fun updateTodoItem(ctx: RequestContext, id: String, input: UpdateTodoItemInput): Boolean {
        return todoItemHandler.update(ObjectId(ctx.appId), ObjectId(id), input)
    }

    fun deleteTodoItem(ctx: RequestContext, id: String): Boolean {
        return todoItemHandler.softDeleteById(ObjectId(ctx.appId), ObjectId(id))
    }

    // ---- Admin batch ----

    fun batchDeleteTodos(ctx: RequestContext, ids: List<String>): Int {
        val appId = ObjectId(ctx.appId)
        ids.forEach { id -> todoItemHandler.softDeleteByTodoId(appId, ObjectId(id)) }
        return todoHandler.deleteByIds(appId, ids.map { ObjectId(it) })
    }

    fun batchUpdateTodos(ctx: RequestContext, patches: List<Pair<String, Map<String, Any?>>>): Int {
        val appId = ObjectId(ctx.appId)
        return todoHandler.updateByIds(appId, patches.toMap())
    }

    fun batchDeleteTodoItems(ctx: RequestContext, ids: List<String>): Int =
        todoItemHandler.softDeleteByIds(ObjectId(ctx.appId), ids.map { ObjectId(it) })
}

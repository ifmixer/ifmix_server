package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import org.bson.types.ObjectId

/**
 * Demo 模块门面，编排 Todo 和 TodoItem 业务操作。
 */
class DemoFacade(
    private val todoHandler: com.ifmix.api.core.modules.demo.handler.TodoEntityHandler,
    private val todoItemHandler: com.ifmix.api.core.modules.demo.handler.TodoItemEntityHandler,
    private val todoService: TodoService,
    private val todoItemService: TodoItemService,
) {

    // ---- Query ----

    fun findTodoById(ctx: RequestContext, id: String): TodoEntity? =
        todoService.findById(ctx, id)

    fun findTodosByIds(ctx: RequestContext, ids: List<String>): List<TodoEntity> =
        todoService.findByIds(ctx, ids)

    fun listTodos(ctx: RequestContext, cursor: String?, limit: Int?): Page<TodoEntity> =
        todoService.findByCursor(ctx, CursorQueryInput(cursor = cursor, limit = limit))

    // ---- Mutation: todo ----

    fun createTodo(ctx: RequestContext, input: CreateTodoInput): String {
        val todoId = todoService.create(ctx, input)
        input.items?.forEach { itemInput ->
            todoItemService.create(ctx, todoId, itemInput)
        }
        return todoId
    }

    fun updateTodo(ctx: RequestContext, id: String, input: UpdateTodoInput): Boolean {
        val result = todoService.update(ctx, id, input)
        input.items?.forEach { mutation ->
            todoItemService.applyMutation(ctx, todoId = id, mutation)
        }
        return result
    }

    fun deleteTodo(ctx: RequestContext, id: String): Boolean {
        todoItemService.deleteByTodoId(ctx, id)
        return todoService.deleteById(ctx, id)
    }

    // ---- Mutation: todoItem ----

    fun createTodoItem(ctx: RequestContext, todoId: String, input: CreateTodoItemInput): String {
        return todoItemService.create(ctx, todoId, input)
    }

    fun updateTodoItem(ctx: RequestContext, id: String, input: UpdateTodoItemInput): Boolean {
        return todoItemService.update(ctx, id, input)
    }

    fun deleteTodoItem(ctx: RequestContext, id: String): Boolean {
        return todoItemService.deleteById(ctx, id)
    }

    // ---- Admin batch ----

    fun batchDeleteTodos(ctx: RequestContext, ids: List<String>): Int {
        ids.forEach { id -> todoItemService.deleteByTodoId(ctx, id) }
        return todoService.deleteByIds(ctx, ids)
    }

    fun batchUpdateTodos(ctx: RequestContext, patches: List<Pair<String, Map<String, Any?>>>): Int =
        todoService.updateByIds(ctx, patches)

    fun batchDeleteTodoItems(ctx: RequestContext, ids: List<String>): Int =
        todoItemService.deleteByIds(ctx, ids)
}

package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository

/**
 * TodoItem 业务逻辑。items 独立存储在 todo_items 集合，由 [TodoItemService] 直接管理。
 */
class TodoItemService(
    private val crud: CRUDService<TodoItemDocument>,
    private val repo: TodoItemRepository,
) {

    fun create(ctx: RequestContext, todoId: String, content: String): String {
        val doc = TodoItemDocument().apply {
            this.todoId = todoId
            this.content = content
            this.done = false
        }
        return crud.createOne(ctx, doc)
    }

    /** DataLoader 批量查询入口：按 todoId 分组返回。 */
    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> =
        repo.findByTodoIds(ctx, todoIds)

    fun getById(ctx: RequestContext, id: String): TodoItemDocument = crud.getById(ctx, id)

    fun findById(ctx: RequestContext, id: String): TodoItemDocument? = crud.findById(ctx, id)

    fun update(ctx: RequestContext, id: String, patch: Any): Boolean = crud.updateById(ctx, id, patch)

    fun deleteById(ctx: RequestContext, id: String): Boolean = crud.deleteById(ctx, id)

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int = crud.deleteByIds(ctx, ids)
}

package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/**
 * TodoItem 业务服务。单表操作，不知道 Todo 的存在。
 */
class TodoItemService(private val repo: TodoItemRepository) {

    fun create(ctx: RequestContext, todoId: String, content: String, done: Boolean = false): String {
        val doc = TodoItemDocument().apply {
            this.todoId = todoId
            this.content = content
            this.done = done
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        return repo.insertOne(ctx, doc)
    }

    fun getById(ctx: RequestContext, id: String): TodoItemDocument =
        repo.findById(ctx, id) ?: throw com.ifmix.api.core.common.http.ApiError(
            com.ifmix.api.core.common.http.ErrorCode.NOT_FOUND, "todo item not found"
        )

    fun findById(ctx: RequestContext, id: String): TodoItemDocument? = repo.findById(ctx, id)

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> =
        repo.findByTodoIds(ctx, todoIds)

    fun update(ctx: RequestContext, id: String, content: String? = null, done: Boolean? = null, unsetFields: List<String>? = null): Boolean {
        val update = Update()
        content?.let { update.set(TodoItemDocument::content, it) }
        done?.let { update.set(TodoItemDocument::done, it) }
        if (update.updateObject.isEmpty() && unsetFields.isNullOrEmpty()) return true
        unsetFields?.forEach { field -> update.unset(field) }
        update.set(BaseDocument::updatedAt, Instant.now())
        return repo.updateById(ctx, id, update)
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean = repo.softDeleteById(ctx, id)

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int = repo.softDeleteByIds(ctx, ids)

    fun deleteByTodoId(ctx: RequestContext, todoId: String): Int = repo.softDeleteByTodoId(ctx, todoId)

    /** 清除指定字段（白名单校验）。TodoItem 目前无可 unset 字段，预留接口。 */
    fun unsetFields(ctx: RequestContext, id: String, fields: List<String>): Boolean {
        val allowed = setOf<String>() // 暂无可 unset 字段
        val valid = fields.filter { it in allowed }
        if (valid.isEmpty()) return true
        val update = Update()
        valid.forEach { update.unset(it) }
        update.set(BaseDocument::updatedAt, Instant.now())
        return repo.updateById(ctx, id, update)
    }
}

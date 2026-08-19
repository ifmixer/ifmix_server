package com.ifmix.api.core.modules.demo.service

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.CreateTodoItemInput
import com.ifmix.api.core.graphql.generated.types.TodoItemMutationInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/**
 * TodoItem 业务服务。单表操作，不知道 Todo 的存在。
 */
class TodoItemService(private val repo: TodoItemRepository) {

    fun create(ctx: RequestContext, todoId: String, input: CreateTodoItemInput): String {
        val doc = TodoItemEntity().apply {
            this.todoId = ObjectId(todoId)
            this.content = input.content
            this.done = input.done ?: false
        }
        return repo.insertOne(ctx, ObjectId(ctx.appId), doc).toHexString()
    }

    fun getById(ctx: RequestContext, id: String): TodoItemEntity =
        repo.findById(ctx, ObjectId(ctx.appId), ObjectId(id))
            ?: throw ApiError(ErrorCode.NOT_FOUND, "todo item not found")

    fun findById(ctx: RequestContext, id: String): TodoItemEntity? =
        repo.findById(ctx, ObjectId(ctx.appId), ObjectId(id))

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemEntity> =
        repo.findByTodoIds(ctx, ObjectId(ctx.appId), todoIds.map { ObjectId(it) })

    fun update(ctx: RequestContext, id: String, input: UpdateTodoItemInput): Boolean {
        val update = Update()
        input.content?.let { update.set(TodoItemEntity::content, it) }
        input.done?.let { update.set(TodoItemEntity::done, it) }
        input.unset?.forEach { update.unset(it) }
        if (update.updateObject.isEmpty()) return true
        update.set(TodoItemEntity::updatedAt, Instant.now())
        return repo.updateById(ctx, ObjectId(ctx.appId), ObjectId(id), update)
    }

    /**
     * 处理嵌套 item mutation（从 todo_update 的 items 字段来）。
     * 语义：无 id → create；delete=true → 删除；否则 set/unset 字段。
     */
    fun applyMutation(ctx: RequestContext, todoId: String, mutation: TodoItemMutationInput) {
        if (mutation.id == null) {
            val content = mutation.content ?: throw ApiError(ErrorCode.INVALID_REQUEST, "item create requires content")
            create(ctx, todoId, CreateTodoItemInput(content = content, done = mutation.done))
        } else if (mutation.delete == true) {
            deleteById(ctx, mutation.id)
        } else {
            val update = Update()
            mutation.content?.let { update.set(TodoItemEntity::content, it) }
            mutation.done?.let { update.set(TodoItemEntity::done, it) }
            mutation.unset?.forEach { update.unset(it) }
            if (update.updateObject.isNotEmpty()) {
                update.set(TodoItemEntity::updatedAt, Instant.now())
                repo.updateById(ctx, ObjectId(ctx.appId), ObjectId(mutation.id), update)
            }
        }
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean =
        repo.softDeleteById(ctx, ObjectId(ctx.appId), ObjectId(id))

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int =
        repo.softDeleteByIds(ctx, ObjectId(ctx.appId), ids.map { ObjectId(it) })

    fun deleteByTodoId(ctx: RequestContext, todoId: String): Int =
        repo.softDeleteByTodoId(ctx, ObjectId(ctx.appId), ObjectId(todoId))
}

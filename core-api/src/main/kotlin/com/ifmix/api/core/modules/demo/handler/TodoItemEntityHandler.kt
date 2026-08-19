package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.common.db.RepoCtx
import com.ifmix.api.core.graphql.generated.types.CreateTodoItemInput
import com.ifmix.api.core.graphql.generated.types.TodoItemMutationInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * TodoItem 实体处理层。封装 todo_items 集合的 CRUD。
 */
@Component
class TodoItemEntityHandler(
    private val repo: TodoItemRepository,
) {
    fun create(appId: ObjectId, todoId: ObjectId, input: CreateTodoItemInput): ObjectId {
        val doc = TodoItemEntity().apply {
            this.todoId = todoId
            this.content = input.content
            this.done = input.done ?: false
        }
        return repo.insertOne(RepoCtx(), appId, doc)
    }

    fun getById(appId: ObjectId, id: ObjectId): TodoItemEntity? =
        repo.findById(RepoCtx(), appId, id)

    fun findByIds(appId: ObjectId, ids: List<ObjectId>): List<TodoItemEntity> =
        repo.findByIds(RepoCtx(), appId, ids)

    fun findByTodoIds(appId: ObjectId, todoIds: List<ObjectId>): List<TodoItemEntity> =
        repo.findByTodoIds(RepoCtx(), appId, todoIds)

    fun update(appId: ObjectId, id: ObjectId, input: UpdateTodoItemInput): Boolean {
        val update = Update()
        input.content?.let { update.set(TodoItemEntity::content, it) }
        input.done?.let { update.set(TodoItemEntity::done, it) }
        input.unset?.forEach { update.unset(it) }
        if (update.updateObject.isEmpty()) return true
        update.set(TodoItemEntity::updatedAt, Instant.now())
        return repo.updateById(RepoCtx(), appId, id, update)
    }

    /**
     * 处理嵌套 item mutation（从 todo_update 的 items 字段来）。
     * 语义：无 id → create；delete=true → 删除；否则 set/unset 字段。
     */
    fun applyMutation(appId: ObjectId, todoId: ObjectId, mutation: TodoItemMutationInput) {
        if (mutation.id == null) {
            val content = mutation.content ?: throw com.ifmix.api.core.common.http.ApiError(
                com.ifmix.api.core.common.http.ErrorCode.INVALID_REQUEST,
                "item create requires content"
            )
            create(appId, todoId, CreateTodoItemInput(content = content, done = mutation.done))
        } else if (mutation.delete == true) {
            softDeleteById(appId, mutation.id)
        } else {
            val update = Update()
            mutation.content?.let { update.set(TodoItemEntity::content, it) }
            mutation.done?.let { update.set(TodoItemEntity::done, it) }
            mutation.unset?.forEach { update.unset(it) }
            if (update.updateObject.isNotEmpty()) {
                update.set(TodoItemEntity::updatedAt, Instant.now())
                repo.updateById(RepoCtx(), appId, mutation.id, update)
            }
        }
    }

    fun softDeleteById(appId: ObjectId, id: ObjectId): Boolean =
        repo.softDeleteById(RepoCtx(), appId, id)

    fun softDeleteByIds(appId: ObjectId, ids: List<ObjectId>): Int =
        repo.softDeleteByIds(RepoCtx(), appId, ids)

    fun softDeleteByTodoId(appId: ObjectId, todoId: ObjectId): Int =
        repo.softDeleteByTodoId(RepoCtx(), appId, todoId)
}

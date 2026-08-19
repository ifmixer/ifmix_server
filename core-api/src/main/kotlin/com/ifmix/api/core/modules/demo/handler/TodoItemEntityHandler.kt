package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.graphql.generated.types.CreateTodoItemInput
import com.ifmix.api.core.graphql.generated.types.TodoItemMutationInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
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
    fun create(appId: String, todoId: String, input: CreateTodoItemInput): String {
        val doc = TodoItemEntity().apply {
            this.todoId = org.bson.types.ObjectId(todoId)
            this.content = input.content
            this.done = input.done ?: false
        }
        return repo.insertOne(RepoCtx(), appId, doc)
    }

    fun getById(appId: String, id: String): TodoItemEntity? =
        repo.findById(RepoCtx(), appId, id)

    fun findByIds(appId: String, ids: List<String>): List<TodoItemEntity> =
        repo.findByIds(RepoCtx(), appId, ids)

    fun findByTodoIds(appId: String, todoIds: List<String>): List<TodoItemEntity> =
        repo.findByTodoIds(RepoCtx(), appId, todoIds)

    fun update(appId: String, id: String, input: UpdateTodoItemInput): Boolean {
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
    fun applyMutation(appId: String, todoId: String, mutation: TodoItemMutationInput) {
        if (mutation.id == null) {
            val set = mutation.set
            val content = set?.content ?: throw com.ifmix.api.core.common.http.ApiError(
                com.ifmix.api.core.common.http.ErrorCode.INVALID_REQUEST,
                "item create requires content"
            )
            create(appId, todoId, CreateTodoItemInput(content = content, done = set?.done))
        } else if (mutation.delete == true) {
            softDeleteById(appId, mutation.id)
        } else {
            val set = mutation.set
            val update = Update()
            set?.content?.let { update.set(TodoItemEntity::content, it) }
            set?.done?.let { update.set(TodoItemEntity::done, it) }
            mutation.unset?.forEach { update.unset(it) }
            if (update.updateObject.isNotEmpty()) {
                update.set(TodoItemEntity::updatedAt, Instant.now())
                repo.updateById(RepoCtx(), appId, mutation.id, update)
            }
        }
    }

    fun softDeleteById(appId: String, id: String): Boolean =
        repo.softDeleteById(RepoCtx(), appId, id)

    fun softDeleteByIds(appId: String, ids: List<String>): Int =
        repo.softDeleteByIds(RepoCtx(), appId, ids)

    fun softDeleteByTodoId(appId: String, todoId: String): Int =
        repo.softDeleteByTodoId(RepoCtx(), appId, todoId)
}

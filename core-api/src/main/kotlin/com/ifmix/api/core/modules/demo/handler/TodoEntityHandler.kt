package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.RepoCtx
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Todo 实体处理层。封装 todos 集合的 CRUD + Redis 缓存。
 */
@Component
class TodoEntityHandler(
    private val repo: TodoRepository,
    private val cache: CacheAside,
) {
    private fun cacheKey(appId: ObjectId, id: ObjectId) = "todo:${appId}:${id}"

    fun findByIdWithCtx(appId: ObjectId, id: ObjectId): TodoEntity? {
        val key = cacheKey(appId, id)
        return cache.getOrLoadNullable(key, TodoEntity::class.java) {
            repo.findById(RepoCtx(), appId, id)
        }
    }

    fun findByIds(appId: ObjectId, ids: List<ObjectId>): List<TodoEntity> =
        repo.findByIds(RepoCtx(), appId, ids)

    fun findByCursor(appId: ObjectId, input: CursorQueryInput): Page<TodoEntity> =
        repo.findByCursor(RepoCtx(), appId, input)

    fun create(appId: ObjectId, input: CreateTodoInput): ObjectId {
        val entity = TodoEntity().apply {
            this.appId = appId
            this.title = input.title
            this.done = false
            this.meta = input.meta
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        return repo.insert(RepoCtx(), entity)
    }

    fun update(appId: ObjectId, id: ObjectId, input: UpdateTodoInput): Boolean {
        val patch = mutableMapOf<String, Any?>()
        input.title?.let { patch["title"] = it }
        input.done?.let { patch["done"] = it }
        input.meta?.let { patch["meta"] = it }
        val result = repo.updateById(RepoCtx(), appId, id, patch, input.unset)
        if (result) cache.evict(cacheKey(appId, id))
        return result
    }

    fun delete(appId: ObjectId, id: ObjectId): Boolean {
        val result = repo.deleteById(RepoCtx(), appId, id)
        if (result) cache.evict(cacheKey(appId, id))
        return result
    }

    fun deleteByIds(appId: ObjectId, ids: List<ObjectId>): Int {
        val count = repo.deleteByIds(RepoCtx(), appId, ids)
        ids.forEach { cache.evict(cacheKey(appId, it)) }
        return count
    }

    fun updateByIds(appId: ObjectId, patches: Map<String, Any>): Int =
        repo.updateByIds(RepoCtx(), appId, patches)
}

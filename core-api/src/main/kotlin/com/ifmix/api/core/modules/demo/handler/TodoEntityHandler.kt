package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.repo.TodoRepository
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
    private fun cacheKey(appId: String, id: String) = "todo:$appId:$id"

    fun findByIdWithCtx(appId: String, id: String): TodoEntity? {
        val key = cacheKey(appId, id)
        return cache.getOrLoadNullable(key, TodoEntity::class.java) {
            repo.findById(RepoCtx(), appId, id)
        }
    }

    fun findByIds(appId: String, ids: List<String>): List<TodoEntity> =
        repo.findByIds(RepoCtx(), appId, ids)

    fun findByCursor(appId: String, input: CursorQueryInput): Page<TodoEntity> =
        repo.findByCursor(RepoCtx(), appId, input)

    fun create(appId: String, input: CreateTodoInput): String {
        val entity = TodoEntity().apply {
            this.title = input.title
            this.done = false
            this.meta = input.meta
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        return repo.insert(RepoCtx(), appId, entity)
    }

    fun update(appId: String, id: String, input: UpdateTodoInput): Boolean {
        val patch = mutableMapOf<String, Any?>()
        input.title?.let { patch["title"] = it }
        input.done?.let { patch["done"] = it }
        input.meta?.let { patch["meta"] = it }
        val result = repo.updateById(RepoCtx(), appId, id, patch, input.unset)
        if (result) cache.evict(cacheKey(appId, id))
        return result
    }

    fun delete(appId: String, id: String): Boolean {
        val result = repo.deleteById(RepoCtx(), appId, id)
        if (result) cache.evict(cacheKey(appId, id))
        return result
    }

    fun deleteByIds(appId: String, ids: List<String>): Int {
        val count = repo.deleteByIds(RepoCtx(), appId, ids)
        ids.forEach { cache.evict(cacheKey(appId, it)) }
        return count
    }

    fun updateByIds(appId: String, patches: Map<String, Any>): Int =
        repo.updateByIds(RepoCtx(), appId, patches)
}

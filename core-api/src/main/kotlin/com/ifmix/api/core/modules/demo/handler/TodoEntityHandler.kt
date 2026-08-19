package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.FilterGroup
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
    private fun cacheKey(appId: String, id: String) = "todo:$appId:$id"

    fun findByIdWithCtx(ctx: RequestContext, id: String): TodoEntity? {
        val key = cacheKey(ctx.appId, id)
        return cache.getOrLoadNullable(key, TodoEntity::class.java) {
            repo.findById(RepoCtx(), ctx.appId, id)
        }
    }

    fun findByIds(ctx: RequestContext, ids: List<String>): List<TodoEntity> =
        repo.findByIds(RepoCtx(), ctx.appId, ids)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput, filter: FilterGroup? = null): Page<TodoEntity> =
        repo.findByCursor(RepoCtx(), ctx.appId, input, filter)

    fun create(ctx: RequestContext, input: CreateTodoInput): String {
        val entity = TodoEntity().apply {
            this.title = input.title
            this.done = false
            this.meta = input.meta
            this.authorId = input.authorId?.let { ObjectId(it) } ?: ctx.userId?.let { ObjectId(it) }
            this.userId = ctx.userId?.let { ObjectId(it) }
            this.installId = ctx.installId?.let { ObjectId(it) }
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        return repo.insert(RepoCtx(), ctx.appId, entity)
    }

    fun update(ctx: RequestContext, id: String, input: UpdateTodoInput): Boolean {
        val patch = mutableMapOf<String, Any?>()
        input.title?.let { patch["title"] = it }
        input.done?.let { patch["done"] = it }
        input.meta?.let { patch["meta"] = it }
        input.authorId?.let { patch["authorId"] = it }
        val result = repo.updateById(RepoCtx(), ctx.appId, id, patch, input.unset)
        if (result) cache.evict(cacheKey(ctx.appId, id))
        return result
    }

    fun delete(ctx: RequestContext, id: String): Boolean {
        val result = repo.deleteById(RepoCtx(), ctx.appId, id)
        if (result) cache.evict(cacheKey(ctx.appId, id))
        return result
    }

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int {
        val count = repo.deleteByIds(RepoCtx(), ctx.appId, ids)
        ids.forEach { cache.evict(cacheKey(ctx.appId, it)) }
        return count
    }

    fun updateByIds(ctx: RequestContext, patches: Map<String, Any>): Int =
        repo.updateByIds(RepoCtx(), ctx.appId, patches)
}

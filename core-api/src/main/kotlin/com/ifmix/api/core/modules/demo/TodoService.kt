package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput
import com.ifmix.api.core.modules.demo.entity.TodoEntity

/**
 * Todo 业务服务。只管 todos 集合，不知道 TodoItem 的存在。
 * 聚合由 GraphQL DataLoader 层处理。
 */
class TodoService(
    private val crudOps: CRUDOps<TodoEntity>,
    private val cache: CacheAside,
) {
    private fun cacheKey(ctx: RequestContext, id: String) = "todo:${ctx.appId}:$id"
    private fun repoCtx(ctx: RequestContext) = com.ifmix.api.core.common.http.RepoCtx()

    fun create(ctx: RequestContext, input: CreateTodoInput): String {
        val doc = TodoEntity().apply {
            this.title = input.title
            this.done = false
            this.meta = input.meta
        }
        crudOps.insertOne(repoCtx(ctx), ctx.appId, doc)
        return doc.id.toHexString()
    }

    fun getById(ctx: RequestContext, id: String, useCache: Boolean = true): TodoEntity {
        if (!useCache) return crudOps.getById(repoCtx(ctx), ctx.appId, id)
        return cache.getOrLoad(cacheKey(ctx, id), TodoEntity::class.java) {
            crudOps.getById(repoCtx(ctx), ctx.appId, id)
        }
    }

    fun findById(ctx: RequestContext, id: String, useCache: Boolean = true): TodoEntity? {
        if (!useCache) return crudOps.findById(repoCtx(ctx), ctx.appId, id)
        return cache.getOrLoadNullable(cacheKey(ctx, id), TodoEntity::class.java) {
            crudOps.findById(repoCtx(ctx), ctx.appId, id)
        }
    }

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoEntity> =
        crudOps.findByCursor(repoCtx(ctx), ctx.appId, input)

    fun findByIds(ctx: RequestContext, ids: List<String>): List<TodoEntity> =
        cache.loadMany(
            ids = ids,
            keyOf = { cacheKey(ctx = ctx, id = it) },
            type = TodoEntity::class.java,
            idOf = { it.id.toHexString() },
        ) { missIds -> crudOps.findByIds(repoCtx(ctx), ctx.appId, missIds) }

    fun update(ctx: RequestContext, id: String, input: UpdateTodoInput): Boolean {
        val patch = mutableMapOf<String, Any?>()
        input.title?.let { patch["title"] = it }
        input.done?.let { patch["done"] = it }
        input.meta?.let { patch["meta"] = it }
        if (patch.isEmpty() && input.unset.isNullOrEmpty()) return true
        val result = crudOps.updateByIdWithUnset(repoCtx(ctx), ctx.appId, id, patch, input.unset)
        if (result) cache.evict(cacheKey(ctx, id))
        return result
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean {
        val result = crudOps.deleteById(repoCtx(ctx), ctx.appId, id)
        if (result) cache.evict(cacheKey(ctx, id))
        return result
    }

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int {
        val count = crudOps.deleteByIds(repoCtx(ctx), ctx.appId, ids)
        ids.forEach { cache.evict(cacheKey(ctx, it)) }
        return count
    }

    fun updateByIds(ctx: RequestContext, patches: List<Pair<String, Map<String, Any?>>>): Int {
        val patchMap = patches.toMap()
        val count = crudOps.updateByIds(repoCtx(ctx), ctx.appId, patchMap)
        patchMap.keys.forEach { cache.evict(cacheKey(ctx, it)) }
        return count
    }
}

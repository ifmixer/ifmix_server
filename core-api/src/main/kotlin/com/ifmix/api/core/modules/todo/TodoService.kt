package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.graphql.generated.types.CreateTodoInput
import com.ifmix.api.core.graphql.generated.types.UpdateTodoInput

/**
 * Todo 业务服务。只管 todos 集合，不知道 TodoItem 的存在。
 * 聚合由 GraphQL DataLoader 层处理。
 */
class TodoService(
    private val crud: CRUDService<TodoEntity>,
    private val cache: CacheAside,
) {
    private fun cacheKey(ctx: RequestContext, id: String) = "todo:${ctx.appId}:$id"

    fun create(ctx: RequestContext, input: CreateTodoInput): String {
        val doc = TodoEntity().apply {
            this.title = input.title
            this.done = false
            this.meta = input.meta
            this.userId = ctx.userId
            this.installId = ctx.installId
        }
        return crud.createOne(ctx, doc)
    }

    fun getById(ctx: RequestContext, id: String, useCache: Boolean = true): TodoEntity {
        if (!useCache) return crud.getById(ctx, id)
        return cache.getOrLoad(cacheKey(ctx, id), TodoEntity::class.java) {
            crud.getById(ctx, id)
        }
    }

    fun findById(ctx: RequestContext, id: String, useCache: Boolean = true): TodoEntity? {
        if (!useCache) return crud.findById(ctx, id)
        return cache.getOrLoadNullable(cacheKey(ctx, id), TodoEntity::class.java) {
            crud.findById(ctx, id)
        }
    }

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoEntity> {
        val page = crud.findByCursor(ctx, input)
//        page.items.forEach { cache.put(cacheKey(ctx, it.id.toHexString()), it) }
        return page
    }

    fun findByIds(ctx: RequestContext, ids: List<String>): List<TodoEntity> =
        cache.loadMany(
            ids = ids,
            keyOf = { cacheKey(ctx, it) },
            type = TodoEntity::class.java,
            idOf = { it.id.toHexString() },
        ) { missIds -> crud.findByIds(ctx, missIds) }

    fun update(ctx: RequestContext, id: String, input: UpdateTodoInput): Boolean {
        val patch = mutableMapOf<String, Any?>()
        input.title?.let { patch["title"] = it }
        input.done?.let { patch["done"] = it }
        input.meta?.let { patch["meta"] = it }
        if (patch.isEmpty() && input.unset.isNullOrEmpty()) return true
        val result = crud.updateByIdWithUnset(ctx, id, patch, input.unset)
        if (result) cache.evict(cacheKey(ctx, id))
        return result
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean {
        val result = crud.deleteById(ctx, id)
        if (result) cache.evict(cacheKey(ctx, id))
        return result
    }

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int {
        val count = crud.deleteByIds(ctx, ids)
        ids.forEach { cache.evict(cacheKey(ctx, it)) }
        return count
    }

    fun updateByIds(ctx: RequestContext, patches: List<Pair<String, Map<String, Any?>>>): Int {
        var count = 0
        for ((id, patch) in patches) {
            if (crud.updateById(ctx, id, patch)) {
                cache.evict(cacheKey(ctx, id))
                count++
            }
        }
        return count
    }
}

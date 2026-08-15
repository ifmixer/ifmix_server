package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.CRUDService
import org.springframework.data.mongodb.core.MongoTemplate

/**
 * Todo 业务服务。只管 todos 集合，不知道 TodoItem 的存在。
 * 聚合由 GraphQL DataLoader 层处理。
 */
class TodoService(
    private val crud: CRUDService<TodoDocument>,
    private val mongo: MongoTemplate,
) {

    fun create(ctx: RequestContext, title: String, meta: Map<String, Any?>? = null): String {
        val doc = TodoDocument().apply {
            this.title = title
            this.done = false
            this.meta = meta
            this.userId = ctx.userId
            this.installId = ctx.installId
        }
        return crud.createOne(ctx, doc)
    }

    fun getById(ctx: RequestContext, id: String): TodoDocument = crud.getById(ctx, id)

    fun findById(ctx: RequestContext, id: String): TodoDocument? = crud.findById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoDocument> =
        crud.findByCursor(ctx, input)

    fun findByIds(ctx: RequestContext, ids: List<String>): List<TodoDocument> =
        crud.findByIds(ctx, ids)

    fun update(
        ctx: RequestContext,
        id: String,
        title: String? = null,
        done: Boolean? = null,
        meta: Map<String, Any?>? = null,
    ): Boolean {
        val patch = mutableMapOf<String, Any?>()
        title?.let { patch["title"] = it }
        done?.let { patch["done"] = it }
        meta?.let { patch["meta"] = it }
        if (patch.isEmpty()) return true
        return crud.updateById(ctx, id, patch)
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean = crud.deleteById(ctx, id)

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int = crud.deleteByIds(ctx, ids)

    fun updateByIds(ctx: RequestContext, patches: List<Pair<String, Map<String, Any?>>>): Int {
        var count = 0
        for ((id, patch) in patches) {
            if (crud.updateById(ctx, id, patch)) count++
        }
        return count
    }
}

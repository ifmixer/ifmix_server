package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/**
 * todo 业务逻辑：**组合**持有通用 CRUDAppService（不继承），委托通用 CRUD，只实现定制逻辑
 * （带内嵌 items 的创建、部分更新自动生成）。
 */
class TodoService(
    private val crud: CRUDService<TodoDocument>,
    private val mongo: MongoTemplate,
) {

    /** 创建 todo（内嵌 items 单文档原子写），返回新 id。 */
    fun create(ctx: RequestContext, req: CreateTodoRequest): String {
        val doc = TodoDocument().apply {
            title = req.title!!
            done = false
            items = (req.items ?: emptyList()).map {
                TodoItem(id = ObjectId().toHexString(), content = it.content!!, done = false)
            }.toMutableList()
            // 归属字段：登录用户填 userId，所有请求填 installId
            userId = ctx.userId
            installId = ctx.installId
        }
        return crud.createOne(ctx, doc)
    }

    /**
     * 部分更新 todo。
     * - title / done：非空时 $set
     * - items：局部更新语义（同 Jimmer AssociatedSaveMode.MERGE）——
     *   有 id → 只 $set 该 item 传入的字段（arrayFilters）；
     *   无 id → $push 追加新 item；
     *   未提及的 item 不动。
     */
    fun update(ctx: RequestContext, id: String, patch: UpdateTodoRequest): Boolean {
        if (patch.items == null) return crud.updateById(ctx, id, patch)

        val baseQuery = Query(Criteria.where("_id").`is`(id).and("appId").`is`(ctx.appId))
        val now = Instant.now()

        // --- 1) $set todo 本身的字段 + 已有 items 用 arrayFilters 局部改 ---
        val toUpdate = patch.items.filter { it.id != null }
        val toInsert = patch.items.filter { it.id == null }

        if (toUpdate.isNotEmpty() || patch.title != null || patch.done != null) {
            val update = Update()
            patch.title?.let { update.set("title", it) }
            patch.done?.let { update.set("done", it) }
            update.set("updatedAt", now)

            for ((i, req) in toUpdate.withIndex()) {
                val f = "f$i"
                req.content?.let { update.set("items.\$[$f].content", it) }
                req.done?.let { update.set("items.\$[$f].done", it) }
                update.filterArray(Criteria.where("$f.id").`is`(req.id))
            }

            mongo.updateFirst(baseQuery, update, TodoDocument::class.java)
        }

        // --- 2) $push 追加新 items ---
        if (toInsert.isNotEmpty()) {
            val newItems = toInsert.map {
                TodoItem(id = ObjectId().toHexString(), content = it.content ?: "", done = it.done ?: false)
            }
            val pushUpdate = Update()
                .push("items").each(*newItems.toTypedArray())
                .set("updatedAt", now)
            mongo.updateFirst(baseQuery, pushUpdate, TodoDocument::class.java)
        }

        return true
    }

    fun getById(ctx: RequestContext, id: String): TodoDocument = crud.getById(ctx, id)

    fun findById(ctx: RequestContext, id: String): TodoDocument? = crud.findById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoDocument> =
        crud.findByCursor(ctx, input)

    fun deleteById(ctx: RequestContext, id: String): Boolean = crud.deleteById(ctx, id)

    /** 批量查询，返回命中文档列表（未命中自动跳过）。 */
    fun findByIds(ctx: RequestContext, ids: List<String>): List<TodoDocument> =
        crud.findByIds(ctx, ids)

    /** 批量部分更新，返回修改条数。支持 items 局部更新语义。 */
    fun updateByIds(ctx: RequestContext, patches: List<Pair<String, UpdateTodoRequest>>): Int {
        var count = 0
        for ((id, patch) in patches) {
            if (update(ctx, id, patch)) count++
        }
        return count
    }

    /** 批量删除，返回删除条数。 */
    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int =
        crud.deleteByIds(ctx, ids)

    /**
     * 批量删除 items：按 item id 在匹配文档中移除对应子项。
     * 使用 $pull with $in 原子操作，返回修改的文档数。
     */
    fun deleteItemsByIds(ctx: RequestContext, itemIds: List<String>): Int {
        if (itemIds.isEmpty()) return 0
        val query = Query(Criteria.where("appId").`is`(ctx.appId))
        val update = Update().pull("items", Query(Criteria.where("id").`in`(itemIds)))
        return mongo.updateMulti(query, update, TodoDocument::class.java).modifiedCount.toInt()
    }
}

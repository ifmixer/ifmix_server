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
     * - items：merge 语义 —— 有 id 更新、无 id 新增、未提及保留。
     */
    fun update(ctx: RequestContext, id: String, patch: UpdateTodoRequest): Boolean {
        if (patch.items == null) return crud.updateById(ctx, id, patch)

        val doc = crud.getById(ctx, id)
        val itemsById = doc.items.associateBy { it.id }.toMutableMap()

        for (req in patch.items) {
            val existing = req.id?.let { itemsById[it] }
            if (existing != null) {
                // 原地 merge 非空字段
                req.content?.let { existing.content = it }
                req.done?.let { existing.done = it }
            } else {
                // 新增
                val item = TodoItem(id = ObjectId().toHexString(), content = req.content ?: "", done = req.done ?: false)
                itemsById[item.id] = item
            }
        }

        val query = Query(Criteria.where("_id").`is`(id).and("appId").`is`(ctx.appId))
        val update = Update().set("items", itemsById.values.toList())
        patch.title?.let { update.set("title", it) }
        patch.done?.let { update.set("done", it) }
        update.set("updatedAt", java.time.Instant.now())

        return mongo.updateFirst(query, update, TodoDocument::class.java).modifiedCount > 0
    }

    fun getById(ctx: RequestContext, id: String): TodoDocument = crud.getById(ctx, id)

    fun findById(ctx: RequestContext, id: String): TodoDocument? = crud.findById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoDocument> =
        crud.findByCursor(ctx, input)

    fun deleteById(ctx: RequestContext, id: String): Boolean = crud.deleteById(ctx, id)

    /** 批量查询，返回命中文档列表（未命中自动跳过）。 */
    fun findByIds(ctx: RequestContext, ids: List<String>): List<TodoDocument> =
        crud.findByIds(ctx, ids)

    /** 批量部分更新，返回修改条数。支持 items merge 语义。 */
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
     * 使用 $pull with $in 原子操作，返回修改的文档数（每个匹配文档计为1次修改）。
     */
    fun deleteItemsByIds(ctx: RequestContext, itemIds: List<String>): Int {
        if (itemIds.isEmpty()) return 0
        // 按 appId 过滤（app-scoped 文档）
        val query = Query(Criteria.where("appId").`is`(ctx.appId))
        // 从 items 数组中移除所有匹配 _id 的子项
        val update = Update().pull(
            "items",
            Criteria.where("_id").`in`(itemIds.map { ObjectId(it) })
        )
        return mongo.updateMulti(query, update, TodoDocument::class.java).modifiedCount.toInt()
    }
}

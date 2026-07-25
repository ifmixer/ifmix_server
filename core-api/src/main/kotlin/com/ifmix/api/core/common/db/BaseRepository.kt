package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.mongodb.ReadPreference
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/** 基于 MongoTemplate 的通用 CRUD 基类，不关注租户。 */
open class BaseRepository<T : BaseDocument>(
    protected val mongo: MongoTemplate,
    protected val type: Class<T>,
    protected val softDelete: Boolean,
) {

    /** 子类覆写以注入额外过滤（如租户）。默认无。 */
    protected open fun extraCriteria(ctx: RequestContext): Criteria? = null

    fun insertOne(ctx: RequestContext, entity: T) {
        mongo.insert(entity)
    }

    fun insertMany(ctx: RequestContext, entities: Collection<T>) {
        if (entities.isNotEmpty()) mongo.insert(entities, type)
    }

    /** 未命中/非法 id 返回 null，绝不抛。 */
    fun findById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T? {
        if (invalidId(id)) return null
        val query = buildQuery(idCriteria(ctx, id))
        if (options.preferPrimary) query.withReadPreference(ReadPreference.primary())
        return mongo.findOne(query, type)
    }

    /** 未命中抛 NOT_FOUND，返回非空。 */
    fun getById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T =
        findById(ctx, id, options) ?: throw ApiError(ErrorCode.NOT_FOUND)

    fun updateById(ctx: RequestContext, id: String, patch: Map<String, Any?>): Boolean {
        if (invalidId(id)) return false
        val query = buildQuery(idCriteria(ctx, id))
        val update = Update()
        patch.forEach { (k, v) -> update.set(k, v) }
        update.set("updatedAt", Instant.now())
        return mongo.updateFirst(query, update, type).modifiedCount > 0
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean {
        if (invalidId(id)) return false
        val query = buildQuery(idCriteria(ctx, id))
        return if (softDelete) {
            val update = Update().set("deletedAt", Instant.now()).set("updatedAt", Instant.now())
            mongo.updateFirst(query, update, type).modifiedCount > 0
        } else {
            mongo.remove(query, type).deletedCount > 0
        }
    }

    fun findMany(ctx: RequestContext, cursorQuery: CursorQuery): Page<T> {
        val limit = cursorQuery.effectiveLimit()
        val order = cursorQuery.effectiveOrder()

        val criteria = baseCriteria(ctx).toMutableList()
        val cursor = cursorQuery.cursor
        if (!cursor.isNullOrBlank() && ObjectId.isValid(cursor)) {
            val cursorId = ObjectId(cursor)
            criteria.add(
                if (order == CursorQuery.Order.DESC) Criteria.where("_id").lt(cursorId)
                else Criteria.where("_id").gt(cursorId),
            )
        }

        val direction = if (order == CursorQuery.Order.DESC) Sort.Direction.DESC else Sort.Direction.ASC
        val query = buildQuery(criteria)
            .with(Sort.by(direction, "_id"))
            .limit(limit + 1)

        val rows = mongo.find(query, type)
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        val nextCursor = if (hasMore) items.last().id else null
        return Page(items.toList(), nextCursor, hasMore)
    }

    // ---- helpers ----

    /** 基础过滤：extraCriteria（如租户）+ 软删过滤。 */
    protected fun baseCriteria(ctx: RequestContext): List<Criteria> {
        val list = mutableListOf<Criteria>()
        extraCriteria(ctx)?.let { list.add(it) }
        if (softDelete) list.add(Criteria.where("deletedAt").`is`(null))
        return list
    }

    private fun idCriteria(ctx: RequestContext, id: String): List<Criteria> =
        baseCriteria(ctx) + Criteria.where("_id").`is`(ObjectId(id))

    private fun buildQuery(criteria: List<Criteria>): Query {
        val query = Query()
        when {
            criteria.size == 1 -> query.addCriteria(criteria[0])
            criteria.size > 1 -> query.addCriteria(Criteria().andOperator(*criteria.toTypedArray()))
        }
        return query
    }

    private fun invalidId(id: String?): Boolean = id == null || !ObjectId.isValid(id)
}

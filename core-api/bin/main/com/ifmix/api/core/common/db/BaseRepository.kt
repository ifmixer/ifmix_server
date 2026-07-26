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
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import kotlin.reflect.full.memberProperties

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
        applyReadPreference(query, options)
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

    /**
     * 游标分页。调用方通过 [query] 提供过滤条件（只放 filter，不要设 sort/limit——由本方法接管）；
     * 本方法在其条件上**强制追加**租户 appId（extraCriteria）+ 软删过滤，并接管排序、游标 keyset、
     * limit 上限与读偏好，调用方无法绕过。
     *
     * 支持按任意 [CursorQueryInput.sortBy] 字段排序（默认 _id）。非 _id 排序用 (sortBy, _id) 复合
     * keyset：先按游标 id 取锚点文档拿到其真实类型的 sortBy 值，再构造 `sortBy </> anchor OR
     * (sortBy == anchor AND _id </> cursor)`，保证稳定分页且避免在游标里编码任意类型值。
     */
    fun findByCursor(
        ctx: RequestContext,
        query: Query = Query(),
        input: CursorQueryInput = CursorQueryInput(),
        readOptions: ReadOptions = ReadOptions.DEFAULT,
    ): Page<T> {
        val limit = input.effectiveLimit()
        val desc = input.order == CursorQueryInput.Order.DESC
        val sortField = mongoField(input.sortBy)

        // 强制注入租户 + 软删（调用方无法绕过）
        extraCriteria(ctx)?.let { query.addCriteria(it) }
        if (softDelete) query.addCriteria(Criteria.where("deletedAt").`is`(null))

        // 游标 keyset
        val cursor = input.cursor
        if (!cursor.isNullOrBlank() && ObjectId.isValid(cursor)) {
            val cursorId = ObjectId(cursor)
            if (sortField == "_id") {
                query.addCriteria(
                    if (desc) Criteria.where("_id").lt(cursorId) else Criteria.where("_id").gt(cursorId),
                )
            } else {
                val anchor = findById(ctx, cursor, readOptions)
                val anchorVal = anchor?.let { propertyValue(it, input.sortBy) }
                if (anchorVal != null) {
                    val idCond = if (desc) Criteria.where("_id").lt(cursorId) else Criteria.where("_id").gt(cursorId)
                    val sortCond = if (desc) Criteria.where(sortField).lt(anchorVal) else Criteria.where(sortField).gt(anchorVal)
                    query.addCriteria(
                        Criteria().orOperator(
                            sortCond,
                            Criteria().andOperator(Criteria.where(sortField).`is`(anchorVal), idCond),
                        ),
                    )
                }
            }
        }

        // 排序：(sortBy, _id) 保证游标稳定；_id 排序时不重复
        val dir = if (desc) Sort.Direction.DESC else Sort.Direction.ASC
        query.with(
            if (sortField == "_id") Sort.by(dir, "_id") else Sort.by(dir, sortField).and(Sort.by(dir, "_id")),
        )
        query.limit(limit + 1)
        applyReadPreference(query, readOptions)

        val rows = mongo.find(query, type)
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        val nextCursor = if (hasMore) items.last().id else null
        return Page(items.toList(), nextCursor, hasMore)
    }

    // ---- helpers ----

    /**
     * 读写分离：默认读走 secondaryPreferred（从库，无从库回落主库）。
     * 以下情况强制 primary：显式 preferPrimary（写后回读，read-your-writes）、
     * 或处于事务中（Mongo 事务要求主库读，否则报错）。
     */
    private fun applyReadPreference(query: Query, options: ReadOptions) {
        val forcePrimary = options.preferPrimary || TransactionSynchronizationManager.isActualTransactionActive()
        query.withReadPreference(
            if (forcePrimary) ReadPreference.primary() else ReadPreference.secondaryPreferred(),
        )
    }

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

    /** 排序/游标字段名归一：id → _id。 */
    private fun mongoField(field: String): String = if (field == "id") "_id" else field

    /** 反射读取实体上 sortBy 字段的真实类型值（用于复合 keyset 锚点比较）。 */
    private fun propertyValue(entity: T, field: String): Any? {
        val propName = if (field == "_id" || field == "id") "id" else field
        return entity::class.memberProperties.firstOrNull { it.name == propName }?.getter?.call(entity)
    }
}

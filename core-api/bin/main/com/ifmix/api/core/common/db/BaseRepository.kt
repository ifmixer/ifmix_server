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
import java.util.Base64
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
     * 游标分页。本方法自建查询：强制注入租户 appId（extraCriteria）+ 软删过滤，并接管排序、
     * 游标 keyset、limit 上限与读偏好。
     *
     * 支持按任意 [CursorQueryInput.sortBy] 字段排序（默认 _id）。非 _id 排序用 (sortBy, _id) 复合
     * keyset：nextCursor **自包含**地把 sortBy 值 + _id 编码进去（带类型标记），下次解码即得，
     * **无需回库查锚点**；构造 `sortBy </> v OR (sortBy == v AND _id </> id)` 保证稳定分页。
     * _id 排序时游标就是 id 的 hex（短、向后兼容）。
     */
    fun findByCursor(
        ctx: RequestContext,
        input: CursorQueryInput = CursorQueryInput(),
        readOptions: ReadOptions = ReadOptions.DEFAULT,
    ): Page<T> {
        val limit = input.effectiveLimit()
        val desc = input.order == CursorQueryInput.Order.DESC
        val sortField = mongoField(input.sortBy)

        val query = Query()
        // 强制注入租户 + 软删
        extraCriteria(ctx)?.let { query.addCriteria(it) }
        if (softDelete) query.addCriteria(Criteria.where("deletedAt").`is`(null))

        // 游标 keyset（游标自包含，无需查库）
        val cursor = input.cursor
        if (!cursor.isNullOrBlank()) {
            if (sortField == "_id") {
                if (ObjectId.isValid(cursor)) {
                    val cursorId = ObjectId(cursor)
                    query.addCriteria(
                        if (desc) Criteria.where("_id").lt(cursorId) else Criteria.where("_id").gt(cursorId),
                    )
                }
            } else {
                val decoded = Cursor.decode(cursor)
                val value = decoded?.first
                val idHex = decoded?.second
                if (value != null && idHex != null && ObjectId.isValid(idHex)) {
                    val cursorId = ObjectId(idHex)
                    val idCond = if (desc) Criteria.where("_id").lt(cursorId) else Criteria.where("_id").gt(cursorId)
                    val sortCond = if (desc) Criteria.where(sortField).lt(value) else Criteria.where(sortField).gt(value)
                    query.addCriteria(
                        Criteria().orOperator(
                            sortCond,
                            Criteria().andOperator(Criteria.where(sortField).`is`(value), idCond),
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
        val nextCursor = if (hasMore) encodeNextCursor(items.last(), input.sortBy, sortField) else null
        return Page(items.toList(), nextCursor, hasMore)
    }

    /** _id 排序：游标就是 id hex；否则把 (sortBy 值, id) 自包含编码。 */
    private fun encodeNextCursor(last: T, sortBy: String, sortField: String): String? {
        val id = last.id ?: return null
        return if (sortField == "_id") id else Cursor.encode(propertyValue(last, sortBy), id)
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

    /** 反射读取实体上 sortBy 字段的真实类型值（用于游标编码）。 */
    private fun propertyValue(entity: T, field: String): Any? {
        val propName = if (field == "_id" || field == "id") "id" else field
        return entity::class.memberProperties.firstOrNull { it.name == propName }?.getter?.call(entity)
    }
}

/**
 * 自包含游标编解码：把 `(sortBy 值, _id hex)` 连同类型标记编码为 base64url 令牌，解码即得，无需查库。
 * 支持常见标量类型（字符串/布尔/整数/浮点/时间戳）；解码出的值交给 Spring QueryMapper 按字段类型转换。
 */
private object Cursor {
    private const val SEP = '\u0001'

    fun encode(value: Any?, idHex: String): String {
        val (tag, raw) = tagAndRaw(value)
        val payload = "$tag$SEP$raw$SEP$idHex"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /** 返回 (value, idHex)；解析失败或值为 null 时返回 null。 */
    fun decode(cursor: String): Pair<Any?, String>? = try {
        val payload = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        val parts = payload.split(SEP)
        if (parts.size != 3) null else Pair(fromRaw(parts[0], parts[1]), parts[2])
    } catch (_: Exception) {
        null
    }

    private fun tagAndRaw(v: Any?): Pair<String, String> = when (v) {
        null -> "n" to ""
        is String -> "s" to v
        is Boolean -> "b" to v.toString()
        is Int, is Long -> "l" to v.toString()
        is Double, is Float -> "d" to v.toString()
        is Instant -> "ts" to v.toEpochMilli().toString()
        is java.util.Date -> "ts" to v.time.toString()
        else -> "s" to v.toString()
    }

    private fun fromRaw(tag: String, raw: String): Any? = when (tag) {
        "n" -> null
        "b" -> raw.toBoolean()
        "l" -> raw.toLong()
        "d" -> raw.toDouble()
        "ts" -> Instant.ofEpochMilli(raw.toLong())
        else -> raw
    }
}

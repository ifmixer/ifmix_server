package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.common.http.RequestContext
import com.mongodb.ReadPreference
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.data.mongodb.core.query.inValues
import org.springframework.data.mongodb.core.query.lt
import org.springframework.data.mongodb.core.query.gt
import java.time.Instant
import java.util.Base64
import kotlin.reflect.full.memberProperties

/**
 * 基于 MongoTemplate 的通用 CRUD。构造时反射 [type] 探测能力：
 * - 实现 [AppScoped] → 自动注入 appId 过滤（列表 + by-id，分片键定向）。
 * - 实现 [SoftDeletable] → 删除走 deletedAt 标记，读写自动过滤 deletedAt=null。
 * 另保留 extraCriteria/extraIdCriteria 两个钩子，供模块附加自定义过滤（如 collection 按 collectionId）。
 */
class CRUDOps<T : BaseEntity>(
    protected val mongo: MongoTemplate,
    protected val type: Class<T>,
) {
    protected val appScoped: Boolean = AppScoped::class.java.isAssignableFrom(type)
    protected val softDeletable: Boolean = SoftDeletable::class.java.isAssignableFrom(type)

    /** 附加列表过滤钩子（保留）：模块覆写注入自定义过滤。默认无。 */
    protected open fun extraCriteria(ctx: RepoCtx): Criteria? = null

    /** 附加 by-id 过滤钩子（保留）：模块覆写注入自定义 by-id 过滤。默认无。 */
    protected open fun extraIdCriteria(ctx: RepoCtx): Criteria? = null

    fun insertOne(ctx: RepoCtx, appId: String, entity: T) {
        mongo.insert(entity)
    }

    fun insertMany(ctx: RepoCtx, appId: String, entities: Collection<T>) {
        if (entities.isNotEmpty()) mongo.insert(entities, type)
    }

    /** 未命中/非法 id 返回 null，绝不抛。读偏好取 ctx.readPreference。 */
    fun findById(ctx: RepoCtx, appId: String, id: String): T? {
        if (invalidId(id)) return null
        val query = idQuery(ctx, appId, id)
        applyReadPreference(query, ctx)
        return mongo.findOne(query, type)
    }

    /** 未命中抛 NOT_FOUND，返回非空。 */
    fun getById(ctx: RepoCtx, appId: String, id: String): T =
        findById(ctx, appId, id) ?: throw com.ifmix.api.core.common.http.ApiError(com.ifmix.api.core.common.http.ErrorCode.NOT_FOUND)

    /**
     * 部分更新：自动从 [patch] 生成 Mongo `$set`——非空属性逐个 set。
     * patch 为任意对象（反射非空属性，属性名即字段名）或 `Map<String, Any?>`（键即字段名）。
     * 空 patch 时不写，仅返回是否存在。总会刷新 updatedAt。
     */
    fun updateById(ctx: RepoCtx, appId: String, id: String, patch: Any): Boolean {
        if (invalidId(id)) return false
        val sets: Map<String, Any?> = when (patch) {
            is Map<*, *> -> patch.entries.filter { it.value != null }.associate { it.key.toString() to it.value }
            else -> patch::class.memberProperties
                .mapNotNull { p -> p.getter.call(patch)?.let { p.name to it } }
                .toMap()
        }
        if (sets.isEmpty()) return findById(ctx, appId, id) != null

        val query = idQuery(ctx, appId, id)
        val update = Update()
        sets.forEach { (field, value) -> update.set(field, value) }
        update.set(BaseEntity::updatedAt, Instant.now())
        return mongo.updateFirst(query, update, type).modifiedCount > 0
    }

    /**
     * 部分更新 + $unset：自动从 [patch] 生成 Mongo `$set`，并额外对 [unsetFields] 执行 `$unset`。
     * unsetFields 为空列表时退化为普通 updateById。
     */
    fun updateByIdWithUnset(ctx: RepoCtx, appId: String, id: String, patch: Any, unsetFields: List<String>? = null): Boolean {
        if (invalidId(id)) return false
        val sets: Map<String, Any?> = when (patch) {
            is Map<*, *> -> patch.entries.filter { it.value != null }.associate { it.key.toString() to it.value }
            else -> patch::class.memberProperties
                .mapNotNull { p -> p.getter.call(patch)?.let { p.name to it } }
                .toMap()
        }
        if (sets.isEmpty() && (unsetFields.isNullOrEmpty())) return findById(ctx, appId, id) != null

        val query = idQuery(ctx, appId, id)
        val update = Update()
        sets.forEach { (field, value) -> update.set(field, value) }
        unsetFields?.forEach { field -> update.unset(field) }
        update.set(BaseEntity::updatedAt, Instant.now())
        return mongo.updateFirst(query, update, type).modifiedCount > 0
    }

    fun deleteById(ctx: RepoCtx, appId: String, id: String): Boolean {
        if (invalidId(id)) return false
        val query = idQuery(ctx, appId, id)
        return if (softDeletable) {
            val update = Update().set(SoftDeletable::deletedAt, Instant.now()).set(BaseEntity::updatedAt, Instant.now())
            mongo.updateFirst(query, update, type).modifiedCount > 0
        } else {
            mongo.remove(query, type).deletedCount > 0
        }
    }

    /** 批量按 id 查询：命中则返回，未命中跳过。 */
    fun findByIds(ctx: RepoCtx, appId: String, ids: List<String>): List<T> {
        if (ids.isEmpty()) return emptyList()
        val query = Query()
        if (appScoped) query.addCriteria(Criteria.where("appId").`is`(ObjectId(appId)))
        extraCriteria(ctx)?.let { query.addCriteria(it) }
        if (softDeletable) query.addCriteria(SoftDeletable::deletedAt isEqualTo null)
        query.addCriteria(BaseEntity::id inValues ids.mapNotNull { id -> if (invalidId(id)) null else ObjectId(id) })
        applyReadPreference(query, ctx)
        return mongo.find(query, type)
    }

    /**
     * 批量部分更新：遍历 ids 逐个执行 updateById，返回实际修改条数。
     */
    fun updateByIds(ctx: RepoCtx, appId: String, patches: Map<String, Any>): Int {
        var count = 0
        patches.forEach { (id, patch) ->
            if (updateById(ctx, appId, id, patch)) count++
        }
        return count
    }

    /**
     * 批量删除：遍历 ids 逐个执行 deleteById，返回实际删除条数。
     */
    fun deleteByIds(ctx: RepoCtx, appId: String, ids: List<String>): Int {
        var count = 0
        ids.forEach { id ->
            if (deleteById(ctx, appId, id)) count++
        }
        return count
    }

    /**
     * 游标分页。自建查询：注入租户 appId（若 AppScoped）+ extraCriteria + 软删过滤（若 SoftDeletable），
     * 接管排序、游标 keyset、limit 上限与读偏好。支持按任意 [CursorQueryInput.sortBy] 排序（默认 _id）。
     */
    fun findByCursor(ctx: RepoCtx, appId: String, input: CursorQueryInput = CursorQueryInput(), filterCriteria: org.springframework.data.mongodb.core.query.Criteria = org.springframework.data.mongodb.core.query.Criteria()): Page<T> {
        val limit = input.effectiveLimit()
        val desc = input.order == CursorQueryInput.Order.DESC
        val sortField = mongoField(input.sortBy)

        val query = Query()
        if (appScoped) query.addCriteria(Criteria.where("appId").`is`(ObjectId(appId)))
        extraCriteria(ctx)?.let { query.addCriteria(it) }
        if (softDeletable) query.addCriteria(SoftDeletable::deletedAt isEqualTo null)
        if (filterCriteria.criteriaObject.isNotEmpty()) query.addCriteria(filterCriteria)
        val cursor = input.cursor
        if (!cursor.isNullOrBlank()) {
            if (sortField == "_id") {
                if (ObjectId.isValid(cursor)) {
                    val cursorId = ObjectId(cursor)
                    query.addCriteria(
                        if (desc) BaseEntity::id lt cursorId else BaseEntity::id gt cursorId,
                    )
                }
            } else {
                val decoded = Cursor.decode(cursor)
                val value = decoded?.first
                val idHex = decoded?.second
                if (value != null && idHex != null && ObjectId.isValid(idHex)) {
                    val cursorId = ObjectId(idHex)
                    val idCond = if (desc) BaseEntity::id lt cursorId else BaseEntity::id gt cursorId
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

        val dir = if (desc) Sort.Direction.DESC else Sort.Direction.ASC
        query.with(
            if (sortField == "_id") Sort.by(dir, "_id") else Sort.by(dir, sortField).and(Sort.by(dir, "_id")),
        )
        query.limit(limit + 1)
        applyReadPreference(query, ctx)

        val rows = mongo.find(query, type)
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        val nextCursor = if (hasMore) encodeNextCursor(items.last(), input.sortBy, sortField) else null
        return Page(items.toList(), nextCursor, hasMore)
    }

    // ---- helpers ----

    private fun applyReadPreference(query: Query, ctx: RepoCtx) {
        query.withReadPreference(ctx.readPreference)
    }

    /** 按 id 定位条件：appId 分片键 + extraIdCriteria + _id。不含软删过滤。 */
    private fun idCriteria(ctx: RepoCtx, appId: String, id: String): MutableList<Criteria> {
        val list = mutableListOf<Criteria>()
        if (appScoped) list.add(Criteria.where("appId").`is`(ObjectId(appId)))
        extraIdCriteria(ctx)?.let { list.add(it) }
        list.add(Criteria().andOperator(BaseEntity::id isEqualTo ObjectId(id)))
        return list
    }

    /** by-id 操作查询：idCriteria + 软删过滤（若 SoftDeletable）。 */
    private fun idQuery(ctx: RepoCtx, appId: String, id: String): Query {
        val criteria = idCriteria(ctx, appId, id)
        if (softDeletable) criteria.add(SoftDeletable::deletedAt isEqualTo null)
        return buildQuery(criteria)
    }

    private fun buildQuery(criteria: List<Criteria>): Query {
        val query = Query()
        when {
            criteria.size == 1 -> query.addCriteria(criteria[0])
            criteria.size > 1 -> query.addCriteria(Criteria().andOperator(*criteria.toTypedArray()))
        }
        return query
    }

    private fun invalidId(id: String?): Boolean = id == null || !ObjectId.isValid(id)

    private fun mongoField(field: String): String = if (field == "id") "_id" else field

    private fun encodeNextCursor(last: T, sortBy: String, sortField: String): String {
        val id = last.id.toHexString()
        return if (sortField == "_id") id else Cursor.encode(propertyValue(last, sortBy), id)
    }

    private fun propertyValue(entity: T, field: String): Any? {
        val propName = if (field == "_id" || field == "id") "id" else field
        return entity::class.memberProperties.firstOrNull { it.name == propName }?.getter?.call(entity)
    }
}

/** 自包含游标编解码：把 (sortBy 值, _id hex) 连同类型标记编码为 base64url，解码即得，无需查库。 */
private object Cursor {
    private const val SEP = ''

    fun encode(value: Any?, idHex: String): String {
        val (tag, raw) = tagAndRaw(value)
        val payload = "$tag$SEP$raw$SEP$idHex"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

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

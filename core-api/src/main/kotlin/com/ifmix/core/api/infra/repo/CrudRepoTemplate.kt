package com.ifmix.core.api.infra.repo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.SortDirection
import com.ifmix.core.api.infra.codec.Base58
import com.ifmix.core.api.infra.db.ModuleCtx
import org.babyfish.jimmer.sql.ast.Selection
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.query.KMutableRootQuery
import org.babyfish.jimmer.sql.kt.ast.table.KNonNullTable
import org.babyfish.jimmer.meta.TypedProp
import java.util.UUID
import kotlin.reflect.KClass

/** 从字符串解析主键值。支持 UUID / String，其它类型显式报错（新增主键类型时在此扩展）。 */
@Suppress("UNCHECKED_CAST")
internal fun <ID : Any> parseIdOrNull(idType: KClass<ID>, raw: String): ID? = when (idType) {
    UUID::class -> runCatching { UUID.fromString(raw) }.getOrNull() as ID?
    String::class -> raw as ID
    else -> throw IllegalArgumentException("Unsupported id type: ${idType.qualifiedName}")
}

/**
 * 全局实体 CRUD 模板（无租户隔离）。
 *
 * 用法：
 * ```kotlin
 * @Repository
 * class AuthTenantRepository {
 *     private val tpl = CrudRepoTemplate(AuthTenant::class, UUID::class)
 *     fun findById(ctx: ModuleCtx, id: UUID) = tpl.findById(ctx, id)
 * }
 * ```
 */
class CrudRepoTemplate<E : Any, ID : Comparable<ID>>(
    private val entityType: KClass<E>,
    private val idType: KClass<ID>,
    /** id 字段名（默认 "id"）。 */
    private val id: String = "id",
) {

    fun findById(ctx: ModuleCtx, id: ID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<ID>(this@CrudRepoTemplate.id) eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    fun findByIds(ctx: ModuleCtx, ids: Collection<ID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ctx.sql.createQuery(entityType) {
            where(table.get<ID>(this@CrudRepoTemplate.id) valueIn ids)
            select(table)
        }.execute()
    }

    fun exists(ctx: ModuleCtx, id: ID): Boolean =
        findById(ctx, id) != null

    fun existsByIds(ctx: ModuleCtx, ids: Collection<ID>): Map<ID, Boolean> {
        if (ids.isEmpty()) return emptyMap()
        val found = findByIds(ctx, ids).mapTo(mutableSetOf()) { extractId(it) }
        return ids.associateWith { it in found }
    }

    fun findByCursor(
        ctx: ModuleCtx,
        cursor: ID?,
        limit: Int,
        where: (KMutableRootQuery.ForEntity<E>.() -> Unit)? = null,
    ): Page<E> {
        val rows = ctx.sql.createQuery(entityType) {
            cursor?.let { where(table.get<ID>(this@CrudRepoTemplate.id) lt it) }
            where?.invoke(this)
            orderBy(table.get<ID>(this@CrudRepoTemplate.id).desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(rows, limit) { idString(it) }
    }

    // ===== Write =====

    fun save(ctx: ModuleCtx, entity: E): Boolean =
        ctx.sql.entities.save(entity).totalAffectedRowCount > 0

    fun batchSave(ctx: ModuleCtx, entities: List<E>): Int {
        if (entities.isEmpty()) return 0
        return ctx.sql.entities.saveEntities(entities).totalAffectedRowCount
    }

    // ===== Delete =====

    fun deleteById(ctx: ModuleCtx, id: ID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.get<ID>(this@CrudRepoTemplate.id) eq id)
        }.execute()
        return count > 0
    }

    fun deleteByIds(ctx: ModuleCtx, ids: Collection<ID>): Int {
        if (ids.isEmpty()) return 0
        return ctx.sql.createDelete(entityType) {
            where(table.get<ID>(this@CrudRepoTemplate.id) valueIn ids)
        }.execute()
    }

    // ===== Internal =====

    @Suppress("UNCHECKED_CAST")
    private fun extractId(entity: E): ID {
        val spi = entity as org.babyfish.jimmer.runtime.ImmutableSpi
        return spi.__get(id) as ID
    }

    private fun idString(entity: E): String? = extractId(entity).toString()
}


/**
 * App 级 CRUD 模板（带租户隔离，所有操作强制要求 projectId）。
 * projectId 固定为 String（slug 逻辑外键）；实体自身主键类型 ID 泛型化。
 *
 * 用法：
 * ```kotlin
 * @Repository
 * class TodoRepository {
 *     private val tpl = ProjectCrudRepoTemplate(Todo::class, UUID::class)
 *     fun findById(ctx: ModuleCtx, projectId: String, id: UUID) = tpl.findById(ctx, projectId, id)
 * }
 * ```
 */
class ProjectCrudRepoTemplate<E : Any, ID : Comparable<ID>>(
    private val entityType: KClass<E>,
    private val idType: KClass<ID>,
    /** id 字段名（默认 "id"）。 */
    private val id: String = "id",
    /** projectId 字段名（默认 "projectId"）。 */
    private val projectId: String = "projectId",
) {

    // ===== Read =====

    fun findById(ctx: ModuleCtx, projectId: String, id: ID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<String>(this@ProjectCrudRepoTemplate.projectId) eq projectId)
            where(table.get<ID>(this@ProjectCrudRepoTemplate.id) eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    fun findByIds(ctx: ModuleCtx, projectId: String, ids: Collection<ID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ctx.sql.createQuery(entityType) {
            where(table.get<String>(this@ProjectCrudRepoTemplate.projectId) eq projectId)
            where(table.get<ID>(this@ProjectCrudRepoTemplate.id) valueIn ids)
            select(table)
        }.execute()
    }

    fun exists(ctx: ModuleCtx, projectId: String, id: ID): Boolean =
        findById(ctx, projectId, id) != null

    fun existsByIds(ctx: ModuleCtx, projectId: String, ids: Collection<ID>): Map<ID, Boolean> {
        if (ids.isEmpty()) return emptyMap()
        val found = findByIds(ctx, projectId, ids).mapTo(mutableSetOf()) { extractId(it) }
        return ids.associateWith { it in found }
    }

    fun findByCursor(
        ctx: ModuleCtx,
        projectId: String,
        cursor: ID?,
        limit: Int,
        where: (KMutableRootQuery.ForEntity<E>.() -> Unit)? = null,
    ): Page<E> {
        val rows = ctx.sql.createQuery(entityType) {
            where(table.get<String>(this@ProjectCrudRepoTemplate.projectId) eq projectId)
            cursor?.let { where(table.get<ID>(this@ProjectCrudRepoTemplate.id) lt it) }
            where?.invoke(this)
            orderBy(table.get<ID>(this@ProjectCrudRepoTemplate.id).desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(rows, limit) { idString(it) }
    }

    /**
     * 通用 CommonFindOptions 查询：filter + cursor + sortBy + sortDirection + limit。
     * @param filterable 允许 FilterGroup 过滤的字段白名单
     * @param sortable 允许排序的字段名白名单（默认只允许 id）
     */
    fun findByOptions(
        ctx: ModuleCtx,
        projectId: String,
        findOptions: CommonFindOptions?,
        filterable: List<TypedProp.Scalar<E, *>>,
        sortable: Set<String> = setOf(this.id),
        fetchBy: (KNonNullTable<E>.() -> Selection<E>)? = null,
        where: (KMutableRootQuery.ForEntity<E>.() -> Unit)? = null,
    ): Page<E> {
        val limit = (findOptions?.limit ?: 10).coerceIn(1, 100)
        val sortBy = (findOptions?.sortBy ?: this.id).also {
            require(it in sortable) { "sortBy '$it' not allowed. Allowed: $sortable" }
        }
        val desc = findOptions?.sortDirection != SortDirection.ASC

        // 解析复合 cursor（整体 Base58 编码）: 解码后 sortBy==id → "{id}", 否则 → "{sortValue},{id}"
        val rawCursor = findOptions?.cursor?.let { runCatching { Base58.decodeString(it) }.getOrNull() }
        val cursorParts = rawCursor?.split(",", limit = 2)
        val cursorId: ID?
        val cursorSortValue: String?
        if (sortBy == this.id) {
            cursorId = cursorParts?.firstOrNull()?.let { parseIdOrNull(idType, it) }
            cursorSortValue = null
        } else {
            cursorSortValue = cursorParts?.firstOrNull()
            cursorId = cursorParts?.getOrNull(1)?.let { parseIdOrNull(idType, it) }
        }

        val rows = ctx.sql.createQuery(entityType) {
            where(table.get<String>(this@ProjectCrudRepoTemplate.projectId) eq projectId)
            FilterGroupResolver.apply(this, findOptions?.filter, filterable)
            where?.invoke(this)

            // cursor 条件
            if (cursorSortValue != null && cursorId != null) {
                val sortCol = table.get<Any>(sortBy)
                val idCol = table.get<ID>(this@ProjectCrudRepoTemplate.id)
                // 复合 cursor: (sortBy < val) OR (sortBy = val AND id < cursorId)
                if (desc) {
                    where(
                        or(
                            sql(Boolean::class, "%e < %v") { expression(sortCol); value(cursorSortValue) },
                            and(
                                sql(Boolean::class, "%e = %v") { expression(sortCol); value(cursorSortValue) },
                                idCol lt cursorId
                            )
                        )
                    )
                } else {
                    where(
                        or(
                            sql(Boolean::class, "%e > %v") { expression(sortCol); value(cursorSortValue) },
                            and(
                                sql(Boolean::class, "%e = %v") { expression(sortCol); value(cursorSortValue) },
                                idCol gt cursorId
                            )
                        )
                    )
                }
            } else if (cursorId != null) {
                if (desc) where(table.get<ID>(this@ProjectCrudRepoTemplate.id) lt cursorId)
                else where(table.get<ID>(this@ProjectCrudRepoTemplate.id) gt cursorId)
            }

            // 排序: 主排序字段 + id 做 tiebreaker
            if (desc) {
                orderBy(table.get<Any>(sortBy).desc())
                if (sortBy != this@ProjectCrudRepoTemplate.id) orderBy(table.get<ID>(this@ProjectCrudRepoTemplate.id).desc())
            } else {
                orderBy(table.get<Any>(sortBy).asc())
                if (sortBy != this@ProjectCrudRepoTemplate.id) orderBy(table.get<ID>(this@ProjectCrudRepoTemplate.id).asc())
            }
            if (fetchBy != null) select(fetchBy.invoke(table)) else select(table)
        }.limit(limit + 1).execute()

        return Page.of(rows, limit) { entity ->
            val raw = if (sortBy == this.id) {
                extractId(entity).toString()
            } else {
                "${extractField(entity, sortBy)},${extractId(entity)}"
            }
            Base58.encodeString(raw)
        }
    }

    // ===== Write =====

    fun save(ctx: ModuleCtx, entity: E): Boolean =
        ctx.sql.entities.save(entity).totalAffectedRowCount > 0

    fun batchSave(ctx: ModuleCtx, entities: List<E>): Int {
        if (entities.isEmpty()) return 0
        return ctx.sql.entities.saveEntities(entities).totalAffectedRowCount
    }

    // ===== Delete =====

    fun deleteById(ctx: ModuleCtx, projectId: String, id: ID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.get<String>(this@ProjectCrudRepoTemplate.projectId) eq projectId)
            where(table.get<ID>(this@ProjectCrudRepoTemplate.id) eq id)
        }.execute()
        return count > 0
    }

    fun deleteByIds(ctx: ModuleCtx, projectId: String, ids: Collection<ID>): Int {
        if (ids.isEmpty()) return 0
        return ctx.sql.createDelete(entityType) {
            where(table.get<String>(this@ProjectCrudRepoTemplate.projectId) eq projectId)
            where(table.get<ID>(this@ProjectCrudRepoTemplate.id) valueIn ids)
        }.execute()
    }

    // ===== Internal =====

    @Suppress("UNCHECKED_CAST")
    private fun extractId(entity: E): ID {
        val spi = entity as org.babyfish.jimmer.runtime.ImmutableSpi
        return spi.__get(id) as ID
    }

    private fun extractField(entity: E, field: String): Any? {
        val spi = entity as org.babyfish.jimmer.runtime.ImmutableSpi
        return spi.__get(field)
    }

    private fun idString(entity: E): String? = extractId(entity).toString()
}

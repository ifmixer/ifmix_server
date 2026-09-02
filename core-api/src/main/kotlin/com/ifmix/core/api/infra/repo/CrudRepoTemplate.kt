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

/**
 * 全局实体 CRUD 模板（无租户隔离）。
 *
 * 用法：
 * ```kotlin
 * @Repository
 * class AuthTenantRepository {
 *     private val tpl = CrudRepoTemplate(AuthTenant::class)
 *     fun findById(ctx: ModuleCtx, id: UUID) = tpl.findById(ctx, id)
 * }
 * ```
 */
class CrudRepoTemplate<E : Any>(
    private val entityType: KClass<E>,
    /** id 字段名（默认 "id"）。 */
    private val id: String = "id",
) {

    fun findById(ctx: ModuleCtx, id: UUID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    fun findByIds(ctx: ModuleCtx, ids: Collection<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) valueIn ids)
            select(table)
        }.execute()
    }

    fun exists(ctx: ModuleCtx, id: UUID): Boolean =
        findById(ctx, id) != null

    fun existsByIds(ctx: ModuleCtx, ids: Collection<UUID>): Map<UUID, Boolean> {
        if (ids.isEmpty()) return emptyMap()
        val found = findByIds(ctx, ids).mapTo(mutableSetOf()) { extractId(it) }
        return ids.associateWith { it in found }
    }

    fun findByCursor(
        ctx: ModuleCtx,
        cursor: UUID?,
        limit: Int,
        where: (KMutableRootQuery.ForEntity<E>.() -> Unit)? = null,
    ): Page<E> {
        val rows = ctx.sql.createQuery(entityType) {
            cursor?.let { where(table.get<UUID>(this@CrudRepoTemplate.id) lt it) }
            where?.invoke(this)
            orderBy(table.get<UUID>(this@CrudRepoTemplate.id).desc())
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

    fun deleteById(ctx: ModuleCtx, id: UUID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) eq id)
        }.execute()
        return count > 0
    }

    fun deleteByIds(ctx: ModuleCtx, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) valueIn ids)
        }.execute()
    }

    // ===== Internal =====

    @Suppress("UNCHECKED_CAST")
    private fun extractId(entity: E): UUID {
        val spi = entity as org.babyfish.jimmer.runtime.ImmutableSpi
        return spi.__get(id) as UUID
    }

    private fun idString(entity: E): String? = extractId(entity).toString()
}


/**
 * App 级 CRUD 模板（带租户隔离，所有操作强制要求 appId）。
 *
 * 用法：
 * ```kotlin
 * @Repository
 * class TodoRepository {
 *     private val tpl = AppCrudRepoTemplate(Todo::class)
 *     fun findById(ctx: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(ctx, appId, id)
 * }
 * ```
 */
class AppCrudRepoTemplate<E : Any>(
    private val entityType: KClass<E>,
    /** id 字段名（默认 "id"）。 */
    private val id: String = "id",
    /** appId 字段名（默认 "appId"）。 */
    private val appId: String = "appId",
) {

    // ===== Read =====

    fun findById(ctx: ModuleCtx, appId: UUID, id: UUID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@AppCrudRepoTemplate.appId) eq appId)
            where(table.get<UUID>(this@AppCrudRepoTemplate.id) eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    fun findByIds(ctx: ModuleCtx, appId: UUID, ids: Collection<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@AppCrudRepoTemplate.appId) eq appId)
            where(table.get<UUID>(this@AppCrudRepoTemplate.id) valueIn ids)
            select(table)
        }.execute()
    }

    fun exists(ctx: ModuleCtx, appId: UUID, id: UUID): Boolean =
        findById(ctx, appId, id) != null

    fun existsByIds(ctx: ModuleCtx, appId: UUID, ids: Collection<UUID>): Map<UUID, Boolean> {
        if (ids.isEmpty()) return emptyMap()
        val found = findByIds(ctx, appId, ids).mapTo(mutableSetOf()) { extractId(it) }
        return ids.associateWith { it in found }
    }

    fun findByCursor(
        ctx: ModuleCtx,
        appId: UUID,
        cursor: UUID?,
        limit: Int,
        where: (KMutableRootQuery.ForEntity<E>.() -> Unit)? = null,
    ): Page<E> {
        val rows = ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@AppCrudRepoTemplate.appId) eq appId)
            cursor?.let { where(table.get<UUID>(this@AppCrudRepoTemplate.id) lt it) }
            where?.invoke(this)
            orderBy(table.get<UUID>(this@AppCrudRepoTemplate.id).desc())
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
        appId: UUID,
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
        val cursorId: UUID?
        val cursorSortValue: String?
        if (sortBy == this.id) {
            cursorId = cursorParts?.firstOrNull()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            cursorSortValue = null
        } else {
            cursorSortValue = cursorParts?.firstOrNull()
            cursorId = cursorParts?.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }

        val rows = ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@AppCrudRepoTemplate.appId) eq appId)
            FilterGroupResolver.apply(this, findOptions?.filter, filterable)
            where?.invoke(this)

            // cursor 条件
            if (cursorSortValue != null && cursorId != null) {
                val sortCol = table.get<Any>(sortBy)
                val idCol = table.get<UUID>(this@AppCrudRepoTemplate.id)
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
                if (desc) where(table.get<UUID>(this@AppCrudRepoTemplate.id) lt cursorId)
                else where(table.get<UUID>(this@AppCrudRepoTemplate.id) gt cursorId)
            }

            // 排序: 主排序字段 + id 做 tiebreaker
            if (desc) {
                orderBy(table.get<Any>(sortBy).desc())
                if (sortBy != this@AppCrudRepoTemplate.id) orderBy(table.get<UUID>(this@AppCrudRepoTemplate.id).desc())
            } else {
                orderBy(table.get<Any>(sortBy).asc())
                if (sortBy != this@AppCrudRepoTemplate.id) orderBy(table.get<UUID>(this@AppCrudRepoTemplate.id).asc())
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

    fun deleteById(ctx: ModuleCtx, appId: UUID, id: UUID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@AppCrudRepoTemplate.appId) eq appId)
            where(table.get<UUID>(this@AppCrudRepoTemplate.id) eq id)
        }.execute()
        return count > 0
    }

    fun deleteByIds(ctx: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@AppCrudRepoTemplate.appId) eq appId)
            where(table.get<UUID>(this@AppCrudRepoTemplate.id) valueIn ids)
        }.execute()
    }

    // ===== Internal =====

    @Suppress("UNCHECKED_CAST")
    private fun extractId(entity: E): UUID {
        val spi = entity as org.babyfish.jimmer.runtime.ImmutableSpi
        return spi.__get(id) as UUID
    }


    private fun extractField(entity: E, field: String): Any? {
        val spi = entity as org.babyfish.jimmer.runtime.ImmutableSpi
        return spi.__get(field)
    }
    private fun idString(entity: E): String? = extractId(entity).toString()
}

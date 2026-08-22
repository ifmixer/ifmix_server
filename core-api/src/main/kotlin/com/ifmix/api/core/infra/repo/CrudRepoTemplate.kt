package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.infra.db.ModuleCtx
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.util.UUID
import kotlin.reflect.KClass
import org.babyfish.jimmer.sql.kt.ast.query.KMutableRootQuery
import com.ifmix.api.core.dto.common.Page

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

    private fun idString(entity: E): String? = extractId(entity).toString()
}

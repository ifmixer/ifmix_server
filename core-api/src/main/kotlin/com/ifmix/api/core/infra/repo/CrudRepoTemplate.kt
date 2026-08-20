package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.infra.db.SvcCtx
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.util.UUID
import kotlin.reflect.KClass
import org.babyfish.jimmer.sql.kt.ast.query.KMutableRootQuery
import com.ifmix.api.core.dto.common.Page

/**
 * CRUD 操作模板（组合模式）。
 *
 * Repository 不再继承基类，而是持有一个 Template 实例，按需委托通用操作。
 * 所有查询通过 ctx.sql 执行，确保读写分离路由正确。
 *
 * 用法：
 * ```kotlin
 * @Repository
 * class TodoRepository {
 *     private val tpl = CrudRepoTemplate(Todo::class, appId = "appId")
 *
 *     fun findById(ctx: SvcCtx, appId: UUID, id: UUID) = tpl.findById(ctx, appId, id)
 *     fun save(ctx: SvcCtx, entity: Todo) = tpl.save(ctx, entity)
 * }
 * ```
 */
class CrudRepoTemplate<E : Any>(
    private val entityType: KClass<E>,
    /** id 字段名（默认 "id"）。 */
    private val id: String = "id",
    /** appId 字段名。null 表示该实体无租户隔离（全局实体）。 */
    private val appId: String? = "appId",
) {

    // ===== Read =====

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.appId!!) eq appId)
            where(table.get<UUID>(this@CrudRepoTemplate.id) eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    fun findById(ctx: SvcCtx, id: UUID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.appId!!) eq appId)
            where(table.get<UUID>(this@CrudRepoTemplate.id) valueIn ids)
            select(table)
        }.execute()
    }

    fun findByIds(ctx: SvcCtx, ids: Collection<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) valueIn ids)
            select(table)
        }.execute()
    }

    /**
     * 游标分页查询，返回 Page。
     * 内部自动多查 1 条判断 hasMore，调用方传实际 pageSize 即可。
     * @param where 额外 where 条件 lambda（在 appId 和 cursor 条件之后追加）。
     */
    fun findByCursor(
        ctx: SvcCtx,
        appId: UUID,
        cursor: UUID?,
        limit: Int,
        where: (KMutableRootQuery.ForEntity<E>.() -> Unit)? = null,
    ): Page<E> {
        val rows = ctx.sql.createQuery(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.appId!!) eq appId)
            cursor?.let { where(table.get<UUID>(this@CrudRepoTemplate.id) lt it) }
            where?.invoke(this)
            orderBy(table.get<UUID>(this@CrudRepoTemplate.id).desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(rows, limit) {
            idExtractor(it)
        }
    }

    /** 从实体中提取 id 字符串作为游标。 */
    @Suppress("UNCHECKED_CAST")
    private fun idExtractor(entity: E): String? {
        val spi = entity as org.babyfish.jimmer.runtime.ImmutableSpi
        return spi.__get(id)?.toString()
    }

    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        findById(ctx, appId, id) != null

    // ===== Write =====

    fun save(ctx: SvcCtx, entity: E): E =
        ctx.sql.entities.save(entity).modifiedEntity

    fun batchSave(ctx: SvcCtx, entities: List<E>): List<E> {
        if (entities.isEmpty()) return emptyList()
        return entities.map { ctx.sql.entities.save(it).modifiedEntity }
    }

    // ===== Delete =====

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.appId!!) eq appId)
            where(table.get<UUID>(this@CrudRepoTemplate.id) eq id)
        }.execute()
        return count > 0
    }

    fun deleteById(ctx: SvcCtx, id: UUID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) eq id)
        }.execute()
        return count > 0
    }

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.appId!!) eq appId)
            where(table.get<UUID>(this@CrudRepoTemplate.id) valueIn ids)
        }.execute()
    }

    fun deleteByIds(ctx: SvcCtx, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return ctx.sql.createDelete(entityType) {
            where(table.get<UUID>(this@CrudRepoTemplate.id) valueIn ids)
        }.execute()
    }
}

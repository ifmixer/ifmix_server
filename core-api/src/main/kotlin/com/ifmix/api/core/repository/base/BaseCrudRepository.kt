package com.ifmix.api.core.repository.base

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 通用 CRUD Repository。
 * 注入全局唯一的 KSqlClient，事务由 Spring @Transactional 管理。
 */
abstract class BaseCrudRepository<E : Any>(
    protected val sql: KSqlClient,
    protected val entityType: KClass<E>,
) {
    open fun findById(id: UUID): E? =
        sql.entities.findById(entityType, id)

    open fun <V : View<E>> findById(id: UUID, viewType: KClass<V>): V? =
        sql.entities.findById(viewType, id)

    open fun insert(input: Input<E>): E =
        sql.entities.save(input) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity

    open fun update(input: Input<E>): E =
        sql.entities.save(input) {
            setMode(SaveMode.UPDATE_ONLY)
        }.modifiedEntity

    open fun save(input: Input<E>): E =
        sql.entities.save(input).modifiedEntity

    open fun save(entity: E): E =
        sql.entities.save(entity).modifiedEntity

    open fun deleteById(id: UUID) {
        sql.entities.delete(entityType, id)
    }

    open fun findAll(): List<E> =
        sql.entities.findAll(entityType)

    /**
     * 通用游标分页：按 id DESC 排序，cursor 为上一页最后一条的 id。
     * 使用 Jimmer query DSL 执行 SQL 分页，不全量加载。
     *
     * 子类若需要额外过滤条件（如 appId），应覆写此方法。
     */
    open fun findByCursor(input: CursorQueryInput = CursorQueryInput()): Page<E> {
        val limit = input.effectiveLimit()
        val cursor = input.cursor?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }

        val items = sql.createQuery(entityType) {
            if (cursor != null) {
                where(table.getId<UUID>() lt cursor)
            }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        val hasMore = items.size > limit
        val pageItems = if (hasMore) items.take(limit) else items
        // 最后一条的 id 即 nextCursor（实体已在内存，无需再查库）
        val nextCursor = if (hasMore) {
            pageItems.lastOrNull()?.let { getEntityId(it)?.toString() }
        } else null

        return Page(pageItems, nextCursor, hasMore)
    }

    /**
     * 获取实体的 id 值。通过反射获取 id() 方法（Jimmer 生成的接口方法）。
     */
    private fun getEntityId(entity: Any): UUID? {
        return try {
            val method = entity.javaClass.getMethod("id")
            method.invoke(entity) as? UUID
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 面向多租户实体的 Repository。
 * 类型约束要求 E 实现 AppScopedProps，AppScopedFilter 全局自动注入租户过滤。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    sql: KSqlClient,
    entityType: KClass<E>,
) : BaseCrudRepository<E>(sql, entityType)

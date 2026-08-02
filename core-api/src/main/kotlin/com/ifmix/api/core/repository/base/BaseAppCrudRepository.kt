package com.ifmix.api.core.repository.base

import com.ifmix.api.core.infra.types.SortOrder
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.OperationContext
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 面向多租户实体的 Repository。
 * 类型约束要求 E 实现 AppScopedProps。
 * 所有查询/写入自动按 appId 隔离。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    sql: KSqlClient,
    entityType: KClass<E>,
) : BaseCrudRepository<E>(sql, entityType) {

    // ==================== 带 appId 的查询 ====================

    open fun findById(ctx: OperationContext, appId: UUID, id: UUID): E? =
        sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    open fun <V : View<E>> findById(ctx: OperationContext, appId: UUID, id: UUID, viewType: KClass<V>): V? =
        sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table.fetch(viewType))
        }.limit(1).execute().firstOrNull()

    open fun findByIds(ctx: OperationContext, appId: UUID, ids: List<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
            select(table)
        }.execute()
    }

    open fun <V : View<E>> findByIds(ctx: OperationContext, appId: UUID, ids: List<UUID>, viewType: KClass<V>): List<V> {
        if (ids.isEmpty()) return emptyList()
        return sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
            select(table.fetch(viewType))
        }.execute()
    }

    // ==================== 带 appId 的删除 ====================

    open fun deleteForApp(ctx: OperationContext, appId: UUID, id: UUID): Boolean {
        val count = sql.createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
        }.execute()
        return count > 0
    }

    open fun deleteForApp(ctx: OperationContext, appId: UUID, ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        return sql.createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
        }.execute()
    }

    // ==================== 游标分页（带 appId） ====================

    open fun findByCursor(ctx: OperationContext, appId: UUID, input: CursorQueryInput = CursorQueryInput()): Page<E> {
        val limit = input.effectiveLimit()
        val cursor = input.cursor?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }

        val items = sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            if (cursor != null) {
                where(table.getId<UUID>() lt cursor)
            }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        val hasMore = items.size > limit
        val pageItems = if (hasMore) items.take(limit) else items
        val nextCursor = if (hasMore) {
            pageItems.lastOrNull()?.let { getEntityId(it)?.toString() }
        } else null

        return Page(pageItems, nextCursor, hasMore)
    }

    /**
     * 游标分页 + View 投影。
     * 支持按 id / createdAt / updatedAt 排序，cursor 为对应字段的值。
     */
    open fun <V : View<E>> findViewByCursor(
        ctx: OperationContext,
        appId: UUID,
        viewType: KClass<V>,
        input: CursorQueryInput = CursorQueryInput(),
    ): Page<V> {
        val limit = input.effectiveLimit()
        val sortBy = input.effectiveSortBy()
        val isDesc = input.effectiveOrder() == SortOrder.DESC

        val items = sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)

            // 游标过滤
            if (input.cursor != null) {
                when (sortBy) {
                    "createdAt", "updatedAt" -> {
                        val cursorInstant = try {
                            Instant.ofEpochMilli(input.cursor!!.toLong())
                        } catch (_: Exception) { null }
                        if (cursorInstant != null) {
                            val col = table.get<Instant>(sortBy)
                            if (isDesc) where(col lt cursorInstant) else where(col gt cursorInstant)
                        }
                    }
                    else -> {
                        val cursorUuid = try { UUID.fromString(input.cursor!!) } catch (_: Exception) { null }
                        if (cursorUuid != null) {
                            if (isDesc) where(table.getId<UUID>() lt cursorUuid) else where(table.getId<UUID>() gt cursorUuid)
                        }
                    }
                }
            }

            // 排序
            when (sortBy) {
                "createdAt", "updatedAt" -> {
                    val col = table.get<Instant>(sortBy)
                    if (isDesc) orderBy(col.desc()) else orderBy(col.asc())
                    if (isDesc) orderBy(table.getId<UUID>().desc()) else orderBy(table.getId<UUID>().asc())
                }
                else -> {
                    if (isDesc) orderBy(table.getId<UUID>().desc()) else orderBy(table.getId<UUID>().asc())
                }
            }

            select(table.fetch(viewType))
        }.limit(limit + 1).execute()

        val hasMore = items.size > limit
        val pageItems = if (hasMore) items.take(limit) else items
        val nextCursor = if (hasMore) {
            pageItems.lastOrNull()?.let { extractCursor(it, sortBy) }
        } else null

        return Page(pageItems, nextCursor, hasMore)
    }

    // ==================== 辅助 ====================

    protected fun existsForApp(appId: UUID, id: UUID): Boolean =
        sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().isNotEmpty()

    private fun extractCursor(view: Any, sortBy: String): String? {
        return try {
            when (sortBy) {
                "createdAt", "updatedAt" -> {
                    val getter = "get${sortBy.replaceFirstChar { it.uppercase() }}"
                    val value = view.javaClass.getMethod(getter).invoke(view) as? Instant
                    value?.toEpochMilli()?.toString()
                }
                else -> {
                    val value = view.javaClass.getMethod("getId").invoke(view) as? UUID
                    value?.toString()
                }
            }
        } catch (_: Exception) { null }
    }
}

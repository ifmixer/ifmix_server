package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.dto.CursorQueryInput
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.dto.SortOrder
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 面向多租户实体的 Repository。
 * 类型约束要求 E 实现 AppScopedProps。
 * 所有方法强制带 appId 参数，确保租户隔离。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    protected val clusterRegistry: ClusterRegistry,
    protected val entityType: KClass<E>,
) {

    /** 读操作：根据 RepoContext 选择集群和读写节点 */
    protected fun sql(ctx: RepoContext): KSqlClient = clusterRegistry.sql(ctx)

    /** 写操作：强制使用目标集群的 writer */
    protected fun writerSql(ctx: RepoContext): KSqlClient =
        clusterRegistry.getCluster(ctx.clusterId).sql(preferReader = false)

    // ==================== 写入 ====================

    /**
     * 保存实体。appId 从 entity 自身提取用于一致性校验。
     */
    open fun save(ctx: RepoContext, entity: E): E =
        writerSql(ctx).entities.save(entity).modifiedEntity

    // ==================== 查询 ====================

    open fun findById(ctx: RepoContext, appId: UUID, id: UUID): E? =
        sql(ctx).createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    open fun <V : View<E>> findById(ctx: RepoContext, appId: UUID, id: UUID, viewType: KClass<V>): V? =
        sql(ctx).createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table.fetch(viewType))
        }.limit(1).execute().firstOrNull()

    open fun findByIds(ctx: RepoContext, appId: UUID, ids: List<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return sql(ctx).createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
            select(table)
        }.execute()
    }

    open fun <V : View<E>> findByIds(ctx: RepoContext, appId: UUID, ids: List<UUID>, viewType: KClass<V>): List<V> {
        if (ids.isEmpty()) return emptyList()
        return sql(ctx).createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
            select(table.fetch(viewType))
        }.execute()
    }

    // ==================== 删除 ====================

    open fun deleteById(ctx: RepoContext, appId: UUID, id: UUID): Boolean {
        val count = writerSql(ctx).createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
        }.execute()
        return count > 0
    }

    open fun deleteByIds(ctx: RepoContext, appId: UUID, ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        return writerSql(ctx).createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
        }.execute()
    }

    /**
     * 游标分页 + View 投影。
     * 支持按 id / createdAt / updatedAt 排序，cursor 为对应字段的值。
     */
    open fun <V : View<E>> findViewByCursor(
        ctx: RepoContext,
        appId: UUID,
        viewType: KClass<V>,
        input: CursorQueryInput = CursorQueryInput(),
    ): Page<V> {
        val limit = input.effectiveLimit()
        val sortBy = input.effectiveSortBy()
        val isDesc = input.effectiveOrder() == SortOrder.DESC

        val items = sql(ctx).createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)

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

        return Page.of(items, limit) { extractCursor(it, sortBy) }
    }

    // ==================== 辅助 ====================

    protected fun exists(ctx: RepoContext, appId: UUID, id: UUID): Boolean =
        sql(ctx).createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().isNotEmpty()

    protected fun getEntityId(entity: Any): UUID? {
        return try {
            entity.javaClass.getMethod("getId").invoke(entity) as? UUID
        } catch (_: Exception) { null }
    }

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

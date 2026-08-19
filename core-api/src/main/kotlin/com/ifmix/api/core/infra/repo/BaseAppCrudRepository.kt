package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.infra.db.SvcCtx
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 面向多租户实体的 Repository 基类。
 * 类型约束要求 E 实现 AppScopedProps。
 * 所有方法强制带 appId 参数，确保租户隔离。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    protected val sql: KSqlClient,
    protected val entityType: KClass<E>,
) {

    open fun findById(ctx: SvcCtx, appId: UUID, id: UUID): E? =
        sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    open fun findByIds(ctx: SvcCtx, appId: UUID, ids: List<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { id -> findById(ctx, appId, id) }
    }

    open fun save(ctx: SvcCtx, entity: E): E =
        sql.entities.save(entity).modifiedEntity

    open fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean {
        val count = sql.createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
        }.execute()
        return count > 0
    }

    open fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        findById(ctx, appId, id) != null
}

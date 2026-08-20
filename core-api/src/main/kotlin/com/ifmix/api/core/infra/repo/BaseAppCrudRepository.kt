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
 *
 * 注意：所有查询使用 ctx.sql（路由后的 KSqlClient），
 * 而非构造器注入的 sql，以确保读写分离正确生效。
 * 构造器 sql 仅用于 KSP 类型推断（entityType 注册）。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    protected val sql: KSqlClient,
    protected val entityType: KClass<E>,
) {

    open fun findById(ctx: SvcCtx, appId: UUID, id: UUID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    open fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        return ctx.sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
            select(table)
        }.execute()
    }

    open fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<E> {
        return ctx.sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            cursor?.let { where(table.getId<UUID>() lt it) }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit).execute()
    }

    open fun save(ctx: SvcCtx, entity: E): E =
        ctx.sql.entities.save(entity).modifiedEntity

    open fun batchSave(ctx: SvcCtx, entities: List<E>): List<E> {
        if (entities.isEmpty()) return emptyList()
        return entities.map { save(ctx, it) }
    }

    open fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
        }.execute()
        return count > 0
    }

    open fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return ctx.sql.createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
        }.execute()
    }

    open fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        findById(ctx, appId, id) != null
}

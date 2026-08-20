package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.infra.db.ModuleCtx
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 通用 CRUD Repository（无 appId 场景）。
 * 用于 AuthTenant、AuthIdentity 等全局实体。
 */
abstract class BaseCrudRepository<E : Any>(
    protected val sql: KSqlClient,
    protected val entityType: KClass<E>,
) {

    open fun findById(ctx: ModuleCtx, id: UUID): E? =
        ctx.sql.entities.findById(entityType, id)

    open fun findByIds(ctx: ModuleCtx, ids: Collection<UUID>): List<E> =
        if (ids.isEmpty()) emptyList() else ctx.sql.entities.findByIds(entityType, ids.toList())

    open fun save(ctx: ModuleCtx, entity: E): E =
        ctx.sql.entities.save(entity).modifiedEntity

    open fun batchSave(ctx: ModuleCtx, entities: List<E>): List<E> {
        if (entities.isEmpty()) return emptyList()
        return entities.map { save(ctx, it) }
    }

    open fun deleteById(ctx: ModuleCtx, id: UUID): Boolean {
        val count = ctx.sql.createDelete(entityType) {
            where(table.getId<UUID>() eq id)
        }.execute()
        return count > 0
    }

    open fun exists(ctx: ModuleCtx, id: UUID): Boolean =
        findById(ctx, id) != null
}

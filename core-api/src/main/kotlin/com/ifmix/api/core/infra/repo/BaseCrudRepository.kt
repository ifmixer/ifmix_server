package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.infra.db.SvcCtx
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

    open fun findById(ctx: SvcCtx, id: UUID): E? =
        sql.entities.findById(entityType, id)

    open fun findByIds(ctx: SvcCtx, ids: Collection<UUID>): List<E> =
        if (ids.isEmpty()) emptyList() else sql.entities.findByIds(entityType, ids.toList())

    open fun save(ctx: SvcCtx, entity: E): E =
        sql.entities.save(entity).modifiedEntity

    open fun batchSave(ctx: SvcCtx, entities: List<E>): List<E> {
        if (entities.isEmpty()) return emptyList()
        return entities.map { save(ctx, it) }
    }

    open fun deleteById(ctx: SvcCtx, id: UUID): Boolean {
        val count = sql.createDelete(entityType) {
            where(table.getId<UUID>() eq id)
        }.execute()
        return count > 0
    }

    open fun exists(ctx: SvcCtx, id: UUID): Boolean =
        findById(ctx, id) != null
}

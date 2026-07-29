package com.ifmix.api.core.repository.base

import com.ifmix.api.core.entity.AppScopedProps
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
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
    fun findById(id: UUID): E? =
        sql.entities.findById(entityType, id)

    fun <V : View<E>> findById(id: UUID, viewType: KClass<V>): V? =
        sql.entities.findById(viewType, id)

    fun insert(input: Input<E>): E =
        sql.entities.save(input) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity

    fun update(input: Input<E>): E =
        sql.entities.save(input) {
            setMode(SaveMode.UPDATE_ONLY)
        }.modifiedEntity

    fun save(input: Input<E>): E =
        sql.entities.save(input).modifiedEntity

    fun save(entity: E): E =
        sql.entities.save(entity).modifiedEntity

    fun deleteById(id: UUID) {
        sql.entities.delete(entityType, id)
    }

    fun findAll(): List<E> =
        sql.entities.findAll(entityType)
}

/**
 * 面向多租户实体的 Repository。
 * 类型约束要求 E 实现 AppScopedProps，AppScopedFilter 全局自动注入租户过滤。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    sql: KSqlClient,
    entityType: KClass<E>,
) : BaseCrudRepository<E>(sql, entityType)

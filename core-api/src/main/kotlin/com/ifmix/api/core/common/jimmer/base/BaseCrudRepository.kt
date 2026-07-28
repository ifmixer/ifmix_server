package com.ifmix.api.core.common.jimmer.base

import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 通用 CRUD Repository。不假设 appId（全局过滤会在查询时自动应用）。
 * 提供基本的 CRUD 操作。
 */
abstract class BaseCrudRepository<E : Any>(
    private val clusterRegistry: ClusterRegistry,
    protected val entityType: KClass<E>,
) {
    /** 获取当前语境下的 SqlClient */
    protected fun sql(): KSqlClient = clusterRegistry.primary()

    fun findById(id: UUID): E? =
        sql().entities.findById(entityType, id)

    fun <V : View<E>> findById(id: UUID, viewType: KClass<V>): V? =
        sql().entities.findById(viewType, id)

    fun insert(input: Input<E>): E =
        sql().entities.save(input) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity

    fun update(input: Input<E>): E =
        sql().entities.save(input) {
            setMode(SaveMode.UPDATE_ONLY)
        }.modifiedEntity

    fun save(input: Input<E>): E =
        sql().entities.save(input).modifiedEntity

    fun save(entity: E): E =
        sql().entities.save(entity).modifiedEntity

    fun deleteById(id: UUID) {
        sql().entities.delete(entityType, id)
    }

    /** 获取所有实体（用于测试） */
    fun findAll(): List<E> =
        sql().entities.findAll(entityType)
}

/**
 * 面向多租户实体的 Repository。
 * 类型约束要求 E 实现 AppScopedProps，AppScopedFilter 全局自动注入租户过滤。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    clusterRegistry: ClusterRegistry,
    entityType: KClass<E>,
) : BaseCrudRepository<E>(clusterRegistry, entityType)

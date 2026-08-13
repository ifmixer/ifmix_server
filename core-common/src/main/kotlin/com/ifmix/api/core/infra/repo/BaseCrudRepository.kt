package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.dto.CursorQueryInput
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 通用 CRUD Repository。
 * 通过 ClusterRegistry 按 RepoContext 路由到对应集群，读操作优先走 Reader。
 */
abstract class BaseCrudRepository<E : Any>(
    protected val clusterRegistry: ClusterRegistry,
    protected val entityType: KClass<E>,
) {
    protected fun sql(ctx: RepoContext): KSqlClient = clusterRegistry.sql(ctx)

    protected fun writerSql(ctx: RepoContext): KSqlClient =
        clusterRegistry.getCluster(ctx.clusterId).sql(preferReader = false)

    open fun findById(ctx: RepoContext, id: UUID): E? =
        sql(ctx).entities.findById(entityType, id)

    open fun <V : View<E>> findById(ctx: RepoContext, id: UUID, viewType: KClass<V>): V? =
        sql(ctx).entities.findById(viewType, id)

    open fun findByIds(ctx: RepoContext, ids: List<UUID>): List<E> =
        if (ids.isEmpty()) emptyList() else sql(ctx).entities.findByIds(entityType, ids)

    open fun <V : View<E>> findByIds(ctx: RepoContext, ids: List<UUID>, viewType: KClass<V>): List<V> =
        if (ids.isEmpty()) emptyList() else sql(ctx).entities.findByIds(viewType, ids)

    open fun insert(ctx: RepoContext, input: Input<E>): E =
        writerSql(ctx).entities.save(input) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity

    open fun insertAll(ctx: RepoContext, inputs: List<Input<E>>): List<E> =
        if (inputs.isEmpty()) emptyList()
        else writerSql(ctx).entities.saveInputs(inputs) {
            setMode(SaveMode.INSERT_ONLY)
        }.items.map { it.modifiedEntity }

    open fun update(ctx: RepoContext, input: Input<E>): E =
        writerSql(ctx).entities.save(input) {
            setMode(SaveMode.UPDATE_ONLY)
        }.modifiedEntity

    open fun updateAll(ctx: RepoContext, inputs: List<Input<E>>): List<E> =
        if (inputs.isEmpty()) emptyList()
        else writerSql(ctx).entities.saveInputs(inputs) {
            setMode(SaveMode.UPDATE_ONLY)
        }.items.map { it.modifiedEntity }

    open fun save(ctx: RepoContext, input: Input<E>): E =
        writerSql(ctx).entities.save(input).modifiedEntity

    open fun save(ctx: RepoContext, entity: E): E =
        writerSql(ctx).entities.save(entity).modifiedEntity

    open fun saveAll(ctx: RepoContext, entities: List<E>): List<E> =
        if (entities.isEmpty()) emptyList()
        else writerSql(ctx).entities.saveEntities(entities).items.map { it.modifiedEntity }

    open fun deleteById(ctx: RepoContext, id: UUID) {
        writerSql(ctx).entities.delete(entityType, id)
    }

    open fun deleteByIds(ctx: RepoContext, ids: List<UUID>) {
        if (ids.isEmpty()) return
        writerSql(ctx).entities.deleteAll(entityType, ids)
    }

    open fun findAll(ctx: RepoContext): List<E> =
        sql(ctx).entities.findAll(entityType)

    /**
     * 通用游标分页：按 id DESC 排序，cursor 为上一页最后一条的 id。
     * 使用 Jimmer query DSL 执行 SQL 分页，不全量加载。
     *
     * 子类若需要额外过滤条件（如 appId），应覆写此方法。
     */
    open fun findByCursor(ctx: RepoContext, input: CursorQueryInput = CursorQueryInput()): Page<E> {
        val limit = input.effectiveLimit()
        val cursor = input.cursor?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }

        val items = sql(ctx).createQuery(entityType) {
            if (cursor != null) {
                where(table.getId<UUID>() lt cursor)
            }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(items, limit) { getEntityId(it)?.toString() }
    }

    /**
     * 获取实体的 id 值。通过反射获取 id() 方法（Jimmer 生成的接口方法）。
     */
    protected fun getEntityId(entity: Any): UUID? {
        return try {
            val method = entity.javaClass.getMethod("getId")
            method.invoke(entity) as? UUID
        } catch (_: Exception) {
            null
        }
    }
}

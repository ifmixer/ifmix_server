package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.infra.db.SvcCtx
import org.jooq.Condition
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.UpdatableRecordImpl
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * 通用 CRUD 操作工具 — 无状态，全局共享。
 * Repo 通过组合注入此 bean，按需调用。
 */
@Component
@Suppress("UNCHECKED_CAST")
class CrudRepoOps {

    // ===== Query =====

    fun <T : Any> findById(
        ctx: SvcCtx,
        table: Table<*>,
        appIdField: TableField<*, *>,
        idField: TableField<*, *>,
        appId: UUID,
        id: UUID,
        type: Class<T>,
        deletedAtField: TableField<*, Instant?>? = null,
    ): T? = ctx.dsl.selectFrom(table)
        .where(baseCond(appIdField, appId, deletedAtField).and((idField as TableField<*, UUID?>).eq(id)))
        .fetchOneInto(type)

    fun <T : Any> findByIds(
        ctx: SvcCtx,
        table: Table<*>,
        appIdField: TableField<*, *>,
        idField: TableField<*, *>,
        appId: UUID,
        ids: Collection<UUID>,
        type: Class<T>,
        deletedAtField: TableField<*, Instant?>? = null,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        return ctx.dsl.selectFrom(table)
            .where(baseCond(appIdField, appId, deletedAtField).and((idField as TableField<*, UUID?>).`in`(ids)))
            .fetchInto(type)
    }

    fun <T : Any> findByCursor(
        ctx: SvcCtx,
        table: Table<*>,
        appIdField: TableField<*, *>,
        idField: TableField<*, *>,
        appId: UUID,
        cursor: UUID?,
        limit: Int,
        type: Class<T>,
        deletedAtField: TableField<*, Instant?>? = null,
    ): List<T> {
        var cond = baseCond(appIdField, appId, deletedAtField)
        if (cursor != null) cond = cond.and((idField as TableField<*, UUID?>).lt(cursor))
        return ctx.dsl.selectFrom(table)
            .where(cond)
            .orderBy(idField.desc())
            .limit(limit)
            .fetchInto(type)
    }

    fun <T : Any, V : Any> findByField(
        ctx: SvcCtx,
        table: Table<*>,
        field: TableField<*, *>,
        values: Collection<V>,
        type: Class<T>,
        deletedAtField: TableField<*, Instant?>? = null,
    ): List<T> {
        if (values.isEmpty()) return emptyList()
        var cond: Condition = (field as TableField<*, V?>).`in`(values)
        if (deletedAtField != null) cond = cond.and(deletedAtField.isNull)
        return ctx.dsl.selectFrom(table)
            .where(cond)
            .fetchInto(type)
    }

    fun exists(
        ctx: SvcCtx,
        table: Table<*>,
        appIdField: TableField<*, *>,
        idField: TableField<*, *>,
        appId: UUID,
        id: UUID,
        deletedAtField: TableField<*, Instant?>? = null,
    ): Boolean = ctx.dsl.fetchExists(
        table,
        baseCond(appIdField, appId, deletedAtField).and((idField as TableField<*, UUID?>).eq(id))
    )

    // ===== Insert =====

    fun <T : Any> insert(ctx: SvcCtx, table: Table<*>, model: T) {
        val record = ctx.dsl.newRecord(table, model)
        ctx.dsl.executeInsert(record as org.jooq.TableRecord<*>)
    }

    fun <T : Any> batchInsert(ctx: SvcCtx, table: Table<*>, models: List<T>) {
        if (models.isEmpty()) return
        val records = models.map { ctx.dsl.newRecord(table, it) as org.jooq.TableRecord<*> }
        records.forEach { ctx.dsl.executeInsert(it) }
    }

    /**
     * 真正的 batch insert（单条 SQL: INSERT INTO ... VALUES (...), (...), ...）。
     * 需要传入具体类型的 Table<R>。
     */
    fun <R : UpdatableRecordImpl<R>, T : Any> batchInsertTyped(ctx: SvcCtx, table: Table<R>, models: List<T>) {
        if (models.isEmpty()) return
        val records = models.map { ctx.dsl.newRecord(table, it) }
        ctx.dsl.batchInsert(records).execute()
    }

    // ===== Partial Update =====

    fun <R : Record> partialUpdate(
        ctx: SvcCtx,
        table: Table<R>,
        appIdField: TableField<R, *>,
        idField: TableField<R, *>,
        appId: UUID,
        id: UUID,
        block: UpdateSetMoreStep<R>.() -> Unit,
    ) {
        val step = ctx.dsl.update(table)
            .set(appIdField as TableField<R, UUID?>, appId) as UpdateSetMoreStep<R>
        step.apply(block)
        step.where((idField as TableField<R, UUID?>).eq(id).and((appIdField as TableField<R, UUID?>).eq(appId)))
            .execute()
    }

    // ===== Delete =====

    fun deleteById(
        ctx: SvcCtx,
        table: Table<*>,
        appIdField: TableField<*, *>,
        idField: TableField<*, *>,
        appId: UUID,
        id: UUID,
        deletedAtField: TableField<*, Instant?>? = null,
    ): Boolean = if (deletedAtField != null) {
        ctx.dsl.update(table)
            .set(deletedAtField as TableField<Nothing, Instant?>, Instant.now())
            .where((appIdField as TableField<Nothing, UUID?>).eq(appId).and((idField as TableField<Nothing, UUID?>).eq(id)))
            .execute() > 0
    } else {
        ctx.dsl.deleteFrom(table)
            .where((appIdField as TableField<Nothing, UUID?>).eq(appId).and((idField as TableField<Nothing, UUID?>).eq(id)))
            .execute() > 0
    }

    fun deleteByIds(
        ctx: SvcCtx,
        table: Table<*>,
        appIdField: TableField<*, *>,
        idField: TableField<*, *>,
        appId: UUID,
        ids: Collection<UUID>,
        deletedAtField: TableField<*, Instant?>? = null,
    ): Int {
        if (ids.isEmpty()) return 0
        return if (deletedAtField != null) {
            ctx.dsl.update(table)
                .set(deletedAtField as TableField<Nothing, Instant?>, Instant.now())
                .where((appIdField as TableField<Nothing, UUID?>).eq(appId).and((idField as TableField<Nothing, UUID?>).`in`(ids)))
                .execute()
        } else {
            ctx.dsl.deleteFrom(table)
                .where((appIdField as TableField<Nothing, UUID?>).eq(appId).and((idField as TableField<Nothing, UUID?>).`in`(ids)))
                .execute()
        }
    }

    // ===== Internal =====

    private fun baseCond(appIdField: TableField<*, *>, appId: UUID, deletedAtField: TableField<*, Instant?>?): Condition {
        var cond: Condition = (appIdField as TableField<*, UUID?>).eq(appId)
        if (deletedAtField != null) cond = cond.and(deletedAtField.isNull)
        return cond
    }
}

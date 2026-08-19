package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.infra.db.SvcCtx
import org.jooq.Condition
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.DSL
import org.jooq.impl.UpdatableRecordImpl
import java.time.Instant
import java.util.UUID

/**
 * 实例级通用 CRUD 操作。
 * 通过 [CrudRepoOpsFactory] 为每个表初始化一次，方法签名简洁。
 *
 * @param table      目标表
 * @param idField    主键字段（UUID）
 * @param appIdField 多租户 appId 字段，null 表示无该字段
 * @param type       目标实体类型
 * @param deletedAtField 软删除时间字段，null 表示硬删除
 */
@Suppress("UNCHECKED_CAST")
class CrudRepoOps<T : Any>(
    private val table: Table<*>,
    private val idField: TableField<*, *>,
    private val appIdField: TableField<*, *>?,
    private val type: Class<T>,
    private val deletedAtField: TableField<*, *>?,
) {

    // ===== Query =====

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): T? {
        require(appIdField != null) { "appIdField is required when calling findById with appId" }
        return ctx.dsl.selectFrom(table)
            .where(baseCond(appIdField, appId, deletedAtField).and((idField as TableField<*, UUID?>).eq(id)))
            .fetchOneInto(type)
    }

    fun findById(ctx: SvcCtx, id: UUID): T? {
        require(appIdField == null) { "findById without appId is only for tables without appIdField" }
        return ctx.dsl.selectFrom(table)
            .where(buildDeletedAtCond(deletedAtField).
            and((idField as TableField<*, UUID?>).eq(id)))
            .fetchOneInto(type)
    }

    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<T> {
        require(appIdField != null) { "appIdField is required when calling findByIds" }
        if (ids.isEmpty()) return emptyList()
        return ctx.dsl.selectFrom(table)
            .where(baseCond(appIdField, appId, deletedAtField).and((idField as TableField<*, UUID?>).`in`(ids)))
            .fetchInto(type)
    }

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<T> {
        require(appIdField != null) { "appIdField is required when calling findByCursor" }
        var cond = baseCond(appIdField, appId, deletedAtField)
        if (cursor != null) cond = cond.and((idField as TableField<*, UUID?>).lt(cursor))
        return ctx.dsl.selectFrom(table)
            .where(cond)
            .orderBy(idField.desc())
            .limit(limit)
            .fetchInto(type)
    }

    fun findByField(
        ctx: SvcCtx,
        field: TableField<*, *>,
        values: Collection<*>,
        fieldType: Class<*>,
    ): List<T> {
        if (values.isEmpty()) return emptyList()
        var cond: Condition = (field as TableField<*, Any?>).`in`(values)
        if (deletedAtField != null) cond = cond.and(deletedAtField.isNull)
        return ctx.dsl.selectFrom(table)
            .where(cond)
            .fetchInto(type)
    }

    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean {
        require(appIdField != null) { "appIdField is required when calling exists" }
        return ctx.dsl.fetchExists(
            table,
            baseCond(appIdField, appId, deletedAtField).and((idField as TableField<*, UUID?>).eq(id))
        )
    }

    // ===== Insert =====

    fun insert(ctx: SvcCtx, model: T) {
        val record = ctx.dsl.newRecord(table, model)
        ctx.dsl.executeInsert(record as org.jooq.TableRecord<*>)
    }

    fun batchInsert(ctx: SvcCtx, models: List<T>) {
        if (models.isEmpty()) return
        val records = models.map { ctx.dsl.newRecord(table, it) as org.jooq.TableRecord<*> }
        records.forEach { ctx.dsl.executeInsert(it) }
    }

    /**
     * 真正的 batch insert（单条 SQL: INSERT INTO ... VALUES (...), (...), ...）。
     */
    fun <R : UpdatableRecordImpl<R>> batchInsertTyped(ctx: SvcCtx, models: List<T>) {
        @Suppress("UNCHECKED_CAST")
        val typedTable = table as Table<R>
        if (models.isEmpty()) return
        val records = models.map { ctx.dsl.newRecord(typedTable, it) }
        ctx.dsl.batchInsert(records).execute()
    }

    // ===== Partial Update =====

    fun partialUpdate(
        ctx: SvcCtx,
        appId: UUID,
        id: UUID,
        block: UpdateSetMoreStep<Record>.() -> Unit,
    ) {
        require(appIdField != null) { "appIdField is required when calling partialUpdate" }
        @Suppress("UNCHECKED_CAST")
        val afield = appIdField as TableField<Record, UUID?>
        @Suppress("UNCHECKED_CAST")
        val ifield = idField as TableField<Record, UUID?>
        val step = ctx.dsl.update(table)
            .set(afield, appId) as UpdateSetMoreStep<Record>
        step.apply(block)
        step.where(ifield.eq(id).and(afield.eq(appId))).execute()
    }

    // ===== Delete =====

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean {
        require(appIdField != null) { "appIdField is required when calling deleteById" }
        @Suppress("UNCHECKED_CAST")
        val afield = appIdField as TableField<Nothing, UUID?>
        @Suppress("UNCHECKED_CAST")
        val ifield = idField as TableField<Nothing, UUID?>
        val dfield = deletedAtField as TableField<Nothing, Instant?>?
        return if (dfield != null) {
            ctx.dsl.update(table)
                .set(dfield, Instant.now())
                .where(afield.eq(appId).and(ifield.eq(id)))
                .execute() > 0
        } else {
            ctx.dsl.deleteFrom(table)
                .where(afield.eq(appId).and(ifield.eq(id)))
                .execute() > 0
        }
    }

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int {
        require(appIdField != null) { "appIdField is required when calling deleteByIds" }
        if (ids.isEmpty()) return 0
        @Suppress("UNCHECKED_CAST")
        val afield = appIdField as TableField<Nothing, UUID?>
        @Suppress("UNCHECKED_CAST")
        val ifield = idField as TableField<Nothing, UUID?>
        val dfield = deletedAtField as TableField<Nothing, Instant?>?
        return if (dfield != null) {
            ctx.dsl.update(table)
                .set(dfield, Instant.now())
                .where(afield.eq(appId).and(ifield.`in`(ids)))
                .execute()
        } else {
            ctx.dsl.deleteFrom(table)
                .where(afield.eq(appId).and(ifield.`in`(ids)))
                .execute()
        }
    }

    // ===== Internal =====

    private fun buildDeletedAtCond(deletedAtField: TableField<*, *>?): Condition =
        if (deletedAtField != null) deletedAtField.isNull else DSL.noCondition()

    private fun baseCond(
        appIdField: TableField<*, *>,
        appId: UUID,
        deletedAtField: TableField<*, *>?,
    ): Condition {
        var cond: Condition = (appIdField as TableField<*, UUID?>).eq(appId)
        if (deletedAtField != null) cond = cond.and(deletedAtField.isNull)
        return cond
    }
}

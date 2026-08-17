package com.ifmix.api.core.infra.jooq

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import java.time.Instant
import java.util.UUID

/**
 * Base CRUD repository for jOOQ-backed entities.
 * Provides appId scoping and optional soft-delete.
 *
 * Uses [Field] (not TableField) so that both codegen TableField references
 * and temporary DSL.field(...) string references work without casting.
 */
abstract class BaseCrudRepo<T : Any>(
    protected val dsl: DSLContext,
    protected val table: Table<*>,
    protected val idField: Field<UUID>,
    protected val appIdField: Field<UUID>,
    private val modelClass: Class<T>,
    /** null = hard delete (no soft-delete column) */
    protected val deletedAtField: Field<Instant?>? = null,
) {

    protected fun baseCondition(appId: UUID): Condition {
        var cond: Condition = appIdField.eq(appId)
        if (deletedAtField != null) {
            cond = cond.and(deletedAtField.isNull)
        }
        return cond
    }

    open fun findById(appId: UUID, id: UUID): T? =
        dsl.selectFrom(table)
            .where(baseCondition(appId).and(idField.eq(id)))
            .fetchOneInto(modelClass)

    open fun findByIds(appId: UUID, ids: Collection<UUID>): List<T> {
        if (ids.isEmpty()) return emptyList()
        return dsl.selectFrom(table)
            .where(baseCondition(appId).and(idField.`in`(ids)))
            .fetchInto(modelClass)
    }

    open fun findByCursor(appId: UUID, cursor: UUID?, limit: Int): List<T> {
        var cond = baseCondition(appId)
        if (cursor != null) {
            cond = cond.and(idField.lt(cursor))
        }
        return dsl.selectFrom(table)
            .where(cond)
            .orderBy(idField.desc())
            .limit(limit)
            .fetchInto(modelClass)
    }

    open fun exists(appId: UUID, id: UUID): Boolean =
        dsl.fetchExists(table, baseCondition(appId).and(idField.eq(id)))

    open fun deleteById(appId: UUID, id: UUID): Boolean =
        if (deletedAtField != null) {
            dsl.update(table)
                .set(deletedAtField, Instant.now())
                .where(appIdField.eq(appId).and(idField.eq(id)))
                .execute() > 0
        } else {
            dsl.deleteFrom(table)
                .where(appIdField.eq(appId).and(idField.eq(id)))
                .execute() > 0
        }

    open fun deleteByIds(appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return if (deletedAtField != null) {
            dsl.update(table)
                .set(deletedAtField, Instant.now())
                .where(appIdField.eq(appId).and(idField.`in`(ids)))
                .execute()
        } else {
            dsl.deleteFrom(table)
                .where(appIdField.eq(appId).and(idField.`in`(ids)))
                .execute()
        }
    }
}

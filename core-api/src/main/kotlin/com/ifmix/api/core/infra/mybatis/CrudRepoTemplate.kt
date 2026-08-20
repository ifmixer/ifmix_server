package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.dto.common.Page
import org.mybatis.dynamic.sql.delete.render.DeleteStatementProvider
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider
import org.mybatis.dynamic.sql.update.render.UpdateStatementProvider
import org.mybatis.dynamic.sql.util.kotlin.GroupingCriteriaCollector
import org.mybatis.dynamic.sql.util.kotlin.KotlinSelectBuilder
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.*
import java.time.Instant
import java.util.UUID

/**
 * MyBatis Dynamic SQL 通用 CRUD 模板。
 *
 * Phase 0: Mapper 由 Spring 注入（通过 @MapperScan），直接传函数引用。
 * Phase 2: 改为从 ModuleCtx.session 动态获取 mapper。
 */
class CrudRepoTemplate<E : Any>(
    private val meta: TableMeta,
    private val selectMany: (SelectStatementProvider) -> List<E>,
    private val selectOne: (SelectStatementProvider) -> E?,
    private val doUpdate: (UpdateStatementProvider) -> Int,
    private val doDelete: (DeleteStatementProvider) -> Int,
) {

    fun findById(appId: UUID, id: UUID): E? {
        val provider = select(meta.allColumns) {
            from(meta.table)
            where {
                meta.id isEqualTo id
                meta.appId?.let { and { it isEqualTo appId } }
                meta.deletedAt?.let { and { it.isNull() } }
            }
        }
        return selectOne(provider)
    }

    fun findByIds(appId: UUID, ids: Collection<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        val provider = select(meta.allColumns) {
            from(meta.table)
            where {
                meta.id isIn ids.toList()
                meta.appId?.let { and { it isEqualTo appId } }
                meta.deletedAt?.let { and { it.isNull() } }
            }
        }
        return selectMany(provider)
    }

    fun findByCursor(
        appId: UUID,
        cursor: UUID?,
        limit: Int,
        extra: (GroupingCriteriaCollector.() -> Unit)? = null,
    ): Page<E> {
        val provider = select(meta.allColumns) {
            from(meta.table)
            where {
                meta.appId?.let { it isEqualTo appId }
                meta.deletedAt?.let { and { it.isNull() } }
                cursor?.let { c -> and { meta.id isLessThan c } }
                extra?.invoke(this)
            }
            orderBy(meta.id.descending())
            limit(limit.toLong() + 1)
        }
        val rows = selectMany(provider)
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.dropLast(1) else rows
        val nextCursor = items.lastOrNull()?.let { extractId(it) }
        return Page(items = items, nextCursor = nextCursor, hasMore = hasMore)
    }

    fun exists(appId: UUID, id: UUID): Boolean = findById(appId, id) != null

    fun softDeleteById(appId: UUID, id: UUID): Boolean {
        check(meta.deletedAt != null) { "No deletedAt column" }
        val provider = update(meta.table) {
            set(meta.deletedAt!!) equalTo Instant.now()
            where {
                meta.id isEqualTo id
                meta.appId?.let { and { it isEqualTo appId } }
            }
        }
        return doUpdate(provider) > 0
    }

    fun softDeleteByIds(appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        check(meta.deletedAt != null) { "No deletedAt column" }
        val provider = update(meta.table) {
            set(meta.deletedAt!!) equalTo Instant.now()
            where {
                meta.id isIn ids.toList()
                meta.appId?.let { and { it isEqualTo appId } }
            }
        }
        return doUpdate(provider)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractId(entity: E): String {
        val prop = entity::class.members.first { it.name == "id" }
        return (prop.call(entity) as UUID).toString()
    }
}

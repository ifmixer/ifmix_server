package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.db.SvcCtx
import org.mybatis.dynamic.sql.AliasableSqlTable
import org.mybatis.dynamic.sql.SqlColumn
import org.mybatis.dynamic.sql.insert.render.InsertStatementProvider
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider
import org.mybatis.dynamic.sql.util.kotlin.CountCompleter
import org.mybatis.dynamic.sql.util.kotlin.SelectCompleter
import org.mybatis.dynamic.sql.util.kotlin.UpdateCompleter
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.countFrom
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.insert
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.selectList
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.selectOne
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.update
import org.mybatis.dynamic.sql.util.mybatis3.CommonCountMapper
import org.mybatis.dynamic.sql.util.mybatis3.CommonUpdateMapper
import java.time.Instant
import java.util.UUID

/**
 * MyBatis Dynamic SQL 通用 CRUD 操作。
 *
 * 两种构造方式：
 * 1. 主构造器 — 传高层扩展函数引用 (如 CoreTodoMapper::select)
 * 2. [forTable] 工厂 — 传 table + columns + 底层 mapper 方法引用，自动绑定
 */
class MybatisCrudOps<T : Any, M : Any>(
    private val idColumn: SqlColumn<UUID>,
    private val appIdColumn: SqlColumn<UUID>? = null,
    private val deletedAtColumn: SqlColumn<Instant>? = null,
    private val mapperFn: (SvcCtx) -> M,
    private val selectOneFn: M.(SelectCompleter) -> T?,
    private val selectListFn: M.(SelectCompleter) -> List<T>,
    private val countFn: M.(CountCompleter) -> Long,
    private val updateFn: M.(UpdateCompleter) -> Int,
    private val insertFn: M.(T) -> Int,
) {

    companion object {
        /**
         * Table-aware factory — 传底层 mapper 方法引用 + table/columns，自动组装。
         *
         * Mapper 必须实现 CommonCountMapper + CommonUpdateMapper，
         * select/insert 通过方法引用传入。
         */
        fun <T : Any, M> forTable(
            table: AliasableSqlTable<*>,
            columns: List<SqlColumn<*>>,
            idColumn: SqlColumn<UUID>,
            appIdColumn: SqlColumn<UUID>? = null,
            deletedAtColumn: SqlColumn<Instant>? = null,
            mapperFn: (SvcCtx) -> M,
            selectMany: (M, SelectStatementProvider) -> List<T>,
            selectOne: (M, SelectStatementProvider) -> T?,
            insert: (M, InsertStatementProvider<T>) -> Int,
        ): MybatisCrudOps<T, M> where M : CommonCountMapper, M : CommonUpdateMapper =
            MybatisCrudOps(
                idColumn = idColumn,
                appIdColumn = appIdColumn,
                deletedAtColumn = deletedAtColumn,
                mapperFn = mapperFn,
                selectOneFn = { completer ->
                    selectOne({ provider -> selectOne(this, provider) }, columns, table, completer)
                },
                selectListFn = { completer ->
                    selectList({ provider -> selectMany(this, provider) }, columns, table, completer)
                },
                countFn = { completer -> countFrom(this::count, table, completer) },
                updateFn = { completer -> update(this::update, table, completer) },
                insertFn = { row ->
                    insert({ provider -> insert(this, provider) }, row, table) {
                        columns.forEach { @Suppress("UNCHECKED_CAST") withMappedColumn(it as SqlColumn<Any>) }
                    }
                },
            )
    }

    fun findById(ctx: SvcCtx, appIdVal: UUID?, idVal: UUID): T? =
        mapperFn(ctx).selectOneFn {
            where {
                appIdColumn?.let { it.isEqualTo(appIdVal!!) }
                idColumn.isEqualTo(idVal)
                deletedAtColumn?.isNull()
            }
        }

    fun findByIds(ctx: SvcCtx, appIdVal: UUID?, ids: Collection<UUID>): List<T> {
        if (ids.isEmpty()) return emptyList()
        return mapperFn(ctx).selectListFn {
            where {
                appIdColumn?.let { it.isEqualTo(appIdVal!!) }
                idColumn.isIn(ids.toList())
                deletedAtColumn?.isNull()
            }
        }
    }

    fun findByCursor(ctx: SvcCtx, appIdVal: UUID?, cursor: UUID?, limit: Int): List<T> =
        mapperFn(ctx).selectListFn {
            where {
                appIdColumn?.let { it.isEqualTo(appIdVal!!) }
                deletedAtColumn?.isNull()
                if (cursor != null) idColumn.isLessThan(cursor)
            }
            orderBy(idColumn.descending())
            limit(limit.toLong())
        }

    fun insert(ctx: SvcCtx, entity: T) {
        mapperFn(ctx).insertFn(entity)
    }

    fun deleteById(ctx: SvcCtx, appIdVal: UUID?, idVal: UUID): Boolean =
        if (deletedAtColumn != null) {
            mapperFn(ctx).updateFn {
                set(deletedAtColumn) equalTo Instant.now()
                where {
                    idColumn.isEqualTo(idVal)
                    appIdColumn?.let { it.isEqualTo(appIdVal!!) }
                    deletedAtColumn.isNull()
                }
            } > 0
        } else {
            throw UnsupportedOperationException("deleteById requires deletedAtColumn for soft delete")
        }

    fun exists(ctx: SvcCtx, appIdVal: UUID?, idVal: UUID): Boolean =
        mapperFn(ctx).countFn {
            where {
                appIdColumn?.let { it.isEqualTo(appIdVal!!) }
                idColumn.isEqualTo(idVal)
                deletedAtColumn?.isNull()
            }
        } > 0
}

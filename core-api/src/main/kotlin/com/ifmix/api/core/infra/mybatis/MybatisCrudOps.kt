package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.db.SvcCtx
import org.mybatis.dynamic.sql.SqlColumn
import org.mybatis.dynamic.sql.util.kotlin.CountCompleter
import org.mybatis.dynamic.sql.util.kotlin.SelectCompleter
import org.mybatis.dynamic.sql.util.kotlin.UpdateCompleter
import java.time.Instant
import java.util.UUID

/**
 * MyBatis Dynamic SQL 通用 CRUD 操作。
 *
 * @param idColumn 主键列（必须）
 * @param appIdColumn 多租户 appId 列（可选，null 表示无多租户）
 * @param deletedAtColumn 软删除时间列（可选，null 表示硬删除）
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
            // 软删除
            mapperFn(ctx).updateFn {
                set(deletedAtColumn) equalTo Instant.now()
                where {
                    idColumn.isEqualTo(idVal)
                    appIdColumn?.let { it.isEqualTo(appIdVal!!) }
                    deletedAtColumn.isNull()
                }
            } > 0
        } else {
            // 无软删除列，不支持通过 ops 删除（由 Repository 自行实现硬删除）
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

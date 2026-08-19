package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.db.SvcCtx
import org.mybatis.dynamic.sql.SqlColumn
import org.mybatis.dynamic.sql.util.kotlin.CountCompleter
import org.mybatis.dynamic.sql.util.kotlin.SelectCompleter
import org.mybatis.dynamic.sql.util.kotlin.UpdateCompleter
import java.time.Instant
import java.util.UUID

/**
 * MyBatis Dynamic SQL 通用多租户软删除 CRUD 操作。
 *
 * 不持有 mapper 实例（mapper 绑定 session，每次请求不同）。
 * 每个方法接收 SvcCtx，内部通过 mapperFn 获取当前 session 的 mapper。
 *
 * Repository 构造时初始化一次即可：
 * ```kotlin
 * private val ops = MybatisCrudOps(
 *     id = id, appId = appId, deletedAt = deletedAt,
 *     mapperFn = { ctx -> ctx.mapper<CoreTodoMapper>() },
 *     selectOneFn = CoreTodoMapper::selectOne,
 *     selectListFn = CoreTodoMapper::select,
 *     countFn = CoreTodoMapper::count,
 *     updateFn = CoreTodoMapper::update,
 *     insertFn = CoreTodoMapper::insert,
 * )
 * ```
 */
class MybatisCrudOps<T : Any, M : Any>(
    private val id: SqlColumn<UUID>,
    private val appId: SqlColumn<UUID>,
    private val deletedAt: SqlColumn<Instant>,
    private val mapperFn: (SvcCtx) -> M,
    private val selectOneFn: M.(SelectCompleter) -> T?,
    private val selectListFn: M.(SelectCompleter) -> List<T>,
    private val countFn: M.(CountCompleter) -> Long,
    private val updateFn: M.(UpdateCompleter) -> Int,
    private val insertFn: M.(T) -> Int,
) {

    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): T? =
        mapperFn(ctx).selectOneFn {
            where {
                appId.isEqualTo(appIdVal)
                id.isEqualTo(idVal)
                deletedAt.isNull()
            }
        }

    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>): List<T> {
        if (ids.isEmpty()) return emptyList()
        return mapperFn(ctx).selectListFn {
            where {
                appId.isEqualTo(appIdVal)
                id.isIn(ids.toList())
                deletedAt.isNull()
            }
        }
    }

    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int): List<T> =
        mapperFn(ctx).selectListFn {
            where {
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
                if (cursor != null) {
                    id.isLessThan(cursor)
                }
            }
            orderBy(id.descending())
            limit(limit.toLong())
        }

    fun insert(ctx: SvcCtx, entity: T) {
        mapperFn(ctx).insertFn(entity)
    }

    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean =
        mapperFn(ctx).updateFn {
            set(deletedAt) equalTo Instant.now()
            where {
                id.isEqualTo(idVal)
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
            }
        } > 0

    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean =
        mapperFn(ctx).countFn {
            where {
                appId.isEqualTo(appIdVal)
                id.isEqualTo(idVal)
                deletedAt.isNull()
            }
        } > 0
}

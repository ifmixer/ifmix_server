package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.db.SvcCtx
import org.mybatis.dynamic.sql.render.RenderingStrategies
import java.time.Instant
import java.util.UUID

/**
 * MyBatis Dynamic SQL 通用 CRUD 操作辅助工具。
 *
 * 提供基于 RenderingStrategies.MYBATIS3 的 SQL 渲染扩展函数，
 * 简化 Repository 层中动态 SQL 的使用。
 */
object SqlRender {
    /** 将动态 SQL 渲染为 MYBATIS3 格式（parameterized SQL + named parameters） */
    inline fun <reified M> SvcCtx.renderSql(block: M.() -> Unit): String =
        throw UnsupportedOperationException("Use the typed overload below")
}

/**
 * MyBatis 事务传播行为（对标 Spring Propagation）。
 */
enum class TxPropagation {
    /** 有事务则加入，无则新建（默认）。 */
    REQUIRED,
    /** 总是新建事务（挂起外层事务）。 */
    REQUIRES_NEW,
    /** 有事务则加入，无则非事务执行。 */
    SUPPORTS,
    /** 无事务执行，有事务则挂起。 */
    NOT_SUPPORTED,
}

/**
 * 通用 MyBatis CRUD 操作封装。
 *
 * 通过函数引用的方式传入 mapper 方法，避免强依赖具体 Mapper 接口。
 * 使用时需在 Repository 中通过 factory lambda 构建。
 *
 * @param selectOneFn    单条查询（带条件）
 * @param selectListFn   列表查询
 * @param insertFn       插入
 * @param deleteFn       删除（软删除）
 * @param existsFn       是否存在
 */
@Suppress("UNCHECKED_CAST")
class MybatisCrudOps<T : Any>(
    private val selectOneFn: (SvcCtx, UUID) -> T?,
    private val selectListFn: (SvcCtx, Collection<UUID>) -> List<T>,
    private val insertFn: (SvcCtx, T) -> Unit,
    private val deleteFn: (SvcCtx, UUID) -> Boolean,
    private val existsFn: (SvcCtx, UUID) -> Boolean,
) {

    fun findById(ctx: SvcCtx, id: UUID): T? = selectOneFn(ctx, id)

    fun findByIds(ctx: SvcCtx, ids: Collection<UUID>): List<T> =
        if (ids.isEmpty()) emptyList() else selectListFn(ctx, ids)

    fun insert(ctx: SvcCtx, record: T) = insertFn(ctx, record)

    fun deleteById(ctx: SvcCtx, id: UUID): Boolean = deleteFn(ctx, id)

    fun exists(ctx: SvcCtx, id: UUID): Boolean = existsFn(ctx, id)
}

package com.ifmix.api.core.modules.demo.resolver

import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.done
import com.ifmix.api.core.entity.demo.id
import com.ifmix.api.core.entity.demo.todoId
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.jimmer.OperationContextHolder
import com.ifmix.api.core.modules.demo.repo.TodoItemMetricsCounts
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.KTransientResolver
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Component
import java.util.UUID

// =============================================================================
// 风格 1: 复合计算字段 — 一个 Resolver 返回整个 metrics 对象
// =============================================================================

/**
 * Jimmer KTransientResolver 演示: 复合计算属性。
 *
 * 解析 Todo.jimmerMetrics，一次 SQL 批量返回所有请求 id 的 metrics。
 * Jimmer 自动合并同一层级的 id 做批量调用（等效于 DataLoader batching）。
 *
 * Bean name = "todoMetricsTransientResolver"（对应 @Transient(ref=...) 引用）。
 */
@Component("todoMetricsTransientResolver")
class TodoMetricsTransientResolver(
    private val mcFactory: ModuleCtxFactory,
) : KTransientResolver<UUID, TodoItemMetricsCounts> {

    override fun resolve(ids: Collection<UUID>): Map<UUID, TodoItemMetricsCounts> {
        val opCtx = OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()

        return mc.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn ids)
            groupBy(table.todoId)
            select(
                table.todoId,
                count(table.id),
                sum(case().match(table.done eq false, 1).otherwise(0)),
                sum(case().match(table.done eq true, 1).otherwise(0)),
            )
        }.execute().associate { (todoId, total, pending, finished) ->
            todoId to TodoItemMetricsCounts(
                itemCount = total.toInt(),
                pendingItemCount = pending ?: 0,
                finishedItemCount = finished ?: 0,
            )
        }
    }

    override fun getDefaultValue(): TodoItemMetricsCounts =
        TodoItemMetricsCounts(itemCount = 0, pendingItemCount = 0, finishedItemCount = 0)
}

// =============================================================================
// 风格 2: 独立计算字段 — 每个字段一个 Resolver
// =============================================================================

/**
 * Todo.jimmerCount — 子项总数。
 */
@Component("todoItemCountResolver")
class TodoItemCountResolver(
    private val mcFactory: ModuleCtxFactory,
) : KTransientResolver<UUID, Int> {

    override fun resolve(ids: Collection<UUID>): Map<UUID, Int> {
        val opCtx = OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()

        return mc.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn ids)
            groupBy(table.todoId)
            select(table.todoId, count(table.id))
        }.execute().associate { (todoId, cnt) -> todoId to cnt.toInt() }
    }

    override fun getDefaultValue(): Int = 0
}

/**
 * Todo.jimmerPendingCount — 未完成子项数。
 */
@Component("todoPendingCountResolver")
class TodoPendingCountResolver(
    private val mcFactory: ModuleCtxFactory,
) : KTransientResolver<UUID, Int> {

    override fun resolve(ids: Collection<UUID>): Map<UUID, Int> {
        val opCtx = OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()

        return mc.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn ids)
            where(table.done eq false)
            groupBy(table.todoId)
            select(table.todoId, count(table.id))
        }.execute().associate { (todoId, cnt) -> todoId to cnt.toInt() }
    }

    override fun getDefaultValue(): Int = 0
}

/**
 * Todo.jimmerFinishCount — 已完成子项数。
 */
@Component("todoFinishedCountResolver")
class TodoFinishedCountResolver(
    private val mcFactory: ModuleCtxFactory,
) : KTransientResolver<UUID, Int> {

    override fun resolve(ids: Collection<UUID>): Map<UUID, Int> {
        val opCtx = OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()

        return mc.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn ids)
            where(table.done eq true)
            groupBy(table.todoId)
            select(table.todoId, count(table.id))
        }.execute().associate { (todoId, cnt) -> todoId to cnt.toInt() }
    }

    override fun getDefaultValue(): Int = 0
}

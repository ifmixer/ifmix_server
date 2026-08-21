package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.todoId
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.id
import com.ifmix.api.core.entity.demo.content
import com.ifmix.api.core.entity.demo.done
import com.ifmix.api.core.entity.demo.note
import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoItemRepository {
    private val tpl = CrudRepoTemplate(TodoItem::class, appId = "appId")

    fun save(mc: ModuleCtx, entity: TodoItem) = tpl.save(mc, entity)
    fun deleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(mc, appId, ids)

    fun findByTodoIds(mc: ModuleCtx, appId: UUID, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return mc.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn todoIds)
            select(table)
        }.execute()
    }

    /**
     * 批量统计 metrics：按 todoId 聚合 itemCount / pendingItemCount / finishedItemCount。
     * DataLoader 用这个方法一次查出一批 todo 的统计。
     *
     * 使用 Jimmer case() + sum() 实现条件计数，等效于:
     * ```sql
     * SELECT todo_id, COUNT(*), SUM(CASE WHEN done=false THEN 1 ELSE 0 END), SUM(CASE WHEN done=true THEN 1 ELSE 0 END)
     * FROM core_demo_item WHERE app_id = ? AND todo_id IN (...) GROUP BY todo_id
     * ```
     */
    fun countByTodoIds(mc: ModuleCtx, appId: UUID, todoIds: Collection<UUID>): Map<UUID, TodoItemMetricsCounts> {
        if (todoIds.isEmpty()) return emptyMap()
        return mc.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn todoIds)
            groupBy(table.todoId)
            select(
                table.todoId,
                count(table.id),
                sum(case()
                    .match(table.done eq false, 1)
                    .otherwise(0)),
                sum(case()
                    .match(table.done eq true, 1)
                    .otherwise(0)),
            )
        }.execute().associate { (todoId, total, pending, finished) ->
            todoId to TodoItemMetricsCounts(
                itemCount = total.toInt(),
                pendingItemCount = pending ?: 0,
                finishedItemCount = finished ?: 0,
            )
        }
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoItemInput): Int {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()

        // 没有任何更新请求
        if (set == null && unset.isEmpty()) return 0

        return mc.sql.createUpdate(TodoItem::class) {
            where(table.appId eq appId)
            where(table.id eq input.id)

            // unset 优先
            if (TodoItemUnsetField.NOTE in unset) {
                set(table.note, null as String?)
            } else {
                set?.note?.let { set(table.note, it) }
            }

            if (TodoItemUnsetField.NOTE !in unset) {
                set?.content?.let { set(table.content, it) }
            }

            if (TodoItemUnsetField.NOTE !in unset) {
                set?.done?.let { set(table.done, it) }
            }
        }.execute()
    }
}

/** DataLoader 批量统计的返回值 */
data class TodoItemMetricsCounts(
    val itemCount: Int,
    val pendingItemCount: Int,
    val finishedItemCount: Int,
)
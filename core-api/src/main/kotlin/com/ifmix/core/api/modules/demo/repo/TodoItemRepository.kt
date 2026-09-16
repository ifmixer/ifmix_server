package com.ifmix.core.api.modules.demo.repo

import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.entity.demo.todoId
import com.ifmix.core.api.entity.demo.projectId
import com.ifmix.core.api.entity.demo.id
import com.ifmix.core.api.entity.demo.content
import com.ifmix.core.api.entity.demo.done
import com.ifmix.core.api.entity.demo.note
import com.ifmix.core.api.generated.types.TodoItemUnsetField
import com.ifmix.core.api.generated.types.UpdateTodoItemInput
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoItemRepository {
    private val tpl = ProjectCrudRepoTemplate(TodoItem::class, UUID::class)

    fun save(mc: ModuleCtx, entity: TodoItem) = tpl.save(mc, entity)
    fun deleteByIds(mc: ModuleCtx, projectId: String, ids: Collection<UUID>): Int = tpl.deleteByIds(mc, projectId, ids)

    fun findByTodoIds(mc: ModuleCtx, projectId: String, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return mc.sql.createQuery(TodoItem::class) {
            where(table.projectId eq projectId)
            where(table.todoId valueIn todoIds)
            select(table)
        }.execute()
    }

    fun countByTodoIds(mc: ModuleCtx, projectId: String, todoIds: Collection<UUID>): Map<UUID, TodoItemCounts> {
        if (todoIds.isEmpty()) return emptyMap()
        return mc.sql.createQuery(TodoItem::class) {
            where(table.projectId eq projectId)
            where(table.todoId valueIn todoIds)
            groupBy(table.todoId)
            select(
                table.todoId,
                count(table.id),
                sum(case().match(table.done eq false, 1).otherwise(0)),
                sum(case().match(table.done eq true, 1).otherwise(0)),
            )
        }.execute().associate { (todoId, total, pending, finished) ->
            todoId to TodoItemCounts(
                itemCount = total.toInt(),
                pendingCount = pending ?: 0,
                finishCount = finished ?: 0,
            )
        }
    }

    fun partialUpdate(mc: ModuleCtx, projectId: String, input: UpdateTodoItemInput): Int {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()

        // 没有任何更新请求
        if (set == null && unset.isEmpty()) return 0

        return mc.sql.createUpdate(TodoItem::class) {
            where(table.projectId eq projectId)
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

data class TodoItemCounts(
    val itemCount: Int,
    val pendingCount: Int,
    val finishCount: Int,
)

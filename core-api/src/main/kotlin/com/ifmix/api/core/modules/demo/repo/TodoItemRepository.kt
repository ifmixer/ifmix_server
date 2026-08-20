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

    fun save(mc: ModuleCtx, entity: TodoItem): TodoItem = tpl.save(mc, entity)
    fun deleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(mc, appId, ids)

    fun findByTodoIds(mc: ModuleCtx, appId: UUID, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return mc.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn todoIds)
            select(table)
        }.execute()
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoItemInput) {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()

        // 没有任何更新请求
        if (set == null && unset.isEmpty()) return

        mc.sql.createUpdate(TodoItem::class) {
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

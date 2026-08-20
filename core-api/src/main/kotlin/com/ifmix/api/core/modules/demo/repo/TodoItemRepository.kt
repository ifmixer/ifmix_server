package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.todoId
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.id
import com.ifmix.api.core.entity.demo.content
import com.ifmix.api.core.entity.demo.done
import com.ifmix.api.core.entity.demo.note
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
        val set = input.set ?: return
        if (set.content == null && set.done == null && set.note == null) return
        mc.sql.createUpdate(TodoItem::class) {
            where(table.appId eq appId)
            where(table.id eq input.id)
            set.content?.let { set(table.content, it) }
            set.done?.let { set(table.done, it) }
            set.note?.let { set(table.note, it) }
        }.execute()
    }
}

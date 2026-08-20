package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.entity.todo.todoId
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.entity.todo.content
import com.ifmix.api.core.entity.todo.done
import com.ifmix.api.core.entity.todo.note
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoItemRepository {
    private val tpl = CrudRepoTemplate(TodoItem::class, appId = "appId")

    fun save(ctx: ModuleCtx, entity: TodoItem): TodoItem = tpl.save(ctx, entity)
    fun deleteByIds(ctx: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(ctx, appId, ids)

    fun findByTodoIds(ctx: ModuleCtx, appId: UUID, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return ctx.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn todoIds)
            select(table)
        }.execute()
    }

    fun partialUpdate(ctx: ModuleCtx, appId: UUID, input: UpdateTodoItemInput) {
        val set = input.set ?: return
        if (set.content == null && set.done == null && set.note == null) return
        ctx.sql.createUpdate(TodoItem::class) {
            where(table.appId eq appId)
            where(table.id eq input.id)
            set.content?.let { set(table.content, it) }
            set.done?.let { set(table.done, it) }
            set.note?.let { set(table.note, it) }
        }.execute()
    }
}

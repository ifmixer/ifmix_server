package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.entity.todo.todoId
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.entity.todo.content
import com.ifmix.api.core.entity.todo.done
import com.ifmix.api.core.entity.todo.note
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoItemRepository {
    private val tpl = CrudRepoTemplate(TodoItem::class, appId = "appId")

    fun save(ctx: SvcCtx, entity: TodoItem): TodoItem = tpl.save(ctx, entity)
    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(ctx, appId, ids)

    fun findByTodoIds(ctx: SvcCtx, appId: UUID, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return ctx.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.todoId valueIn todoIds)
            select(table)
        }.execute()
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, content: String?, done: Boolean?, note: String?) {
        if (content == null && done == null && note == null) return
        ctx.sql.createUpdate(TodoItem::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            content?.let { set(table.content, it) }
            done?.let { set(table.done, it) }
            note?.let { set(table.note, it) }
        }.execute()
    }
}

package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.entity.todo.title
import com.ifmix.api.core.entity.todo.done
import com.ifmix.api.core.entity.todo.note
import com.ifmix.api.core.entity.todo.userId
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import com.ifmix.api.core.generated.types.TodoFilter
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository {
    private val tpl = CrudRepoTemplate(Todo::class, appId = "appId")

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Todo? = tpl.findById(ctx, appId, id)
    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<Todo> = tpl.findByIds(ctx, appId, ids)
    fun save(ctx: SvcCtx, entity: Todo): Todo = tpl.save(ctx, entity)
    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(ctx, appId, id)
    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(ctx, appId, ids)

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): List<Todo> {
        return tpl.findByCursor(ctx, appId, cursor, limit) {
            filter?.done?.let { where(table.done eq it) }
            filter?.userId?.let { where(table.userId eq it) }
        }
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, title: String?, done: Boolean?, note: String?) {
        if (title == null && done == null && note == null) return
        ctx.sql.createUpdate(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            title?.let { set(table.title, it) }
            done?.let { set(table.done, it) }
            note?.let { set(table.note, it) }
        }.execute()
    }
}

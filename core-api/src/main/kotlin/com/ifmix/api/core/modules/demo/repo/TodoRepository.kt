package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoProps
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.entity.todo.title
import com.ifmix.api.core.entity.todo.done
import com.ifmix.api.core.entity.todo.note
import com.ifmix.api.core.entity.todo.userId
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import com.ifmix.api.core.infra.repo.FilterGroupResolver
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository {

    companion object {
        private val tpl = CrudRepoTemplate(Todo::class, appId = "appId")

        /** 允许前端通过 FilterGroup 查询的字段（强类型白名单） */
        val FILTERABLE = listOf(
            TodoProps.TITLE,
            TodoProps.DONE,
            TodoProps.USER_ID,
            TodoProps.NOTE,
            TodoProps.CREATED_AT,
            TodoProps.UPDATED_AT,
        )
    }

    fun findById(ctx: ModuleCtx, appId: UUID, id: UUID): Todo? = tpl.findById(ctx, appId, id)
    fun findByIds(ctx: ModuleCtx, appId: UUID, ids: Collection<UUID>): List<Todo> = tpl.findByIds(ctx, appId, ids)
    fun save(ctx: ModuleCtx, entity: Todo): Todo = tpl.save(ctx, entity)
    fun deleteById(ctx: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(ctx, appId, id)
    fun deleteByIds(ctx: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(ctx, appId, ids)

    fun findByCursor(ctx: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> {
        return tpl.findByCursor(ctx, appId, cursor, limit) {
            filter?.done?.let { where(table.done eq it) }
            filter?.userId?.let { where(table.userId eq it) }
        }
    }

    /**
     * 基于 FilterGroup + cursor 的动态查询。
     */
    fun findByFilter(ctx: ModuleCtx, appId: UUID, filter: FilterGroup?, cursor: UUID?, limit: Int): Page<Todo> {
        val rows = ctx.sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            FilterGroupResolver.apply(this, filter, FILTERABLE)
            cursor?.let { where(table.getId<UUID>() lt it) }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(rows, limit) { it.id.toString() }
    }

    fun partialUpdate(ctx: ModuleCtx, appId: UUID, input: UpdateTodoInput) {
        val set = input.set ?: return
        if (set.title == null && set.done == null && set.note == null) return
        ctx.sql.createUpdate(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq input.id)
            set.title?.let { set(table.title, it) }
            set.done?.let { set(table.done, it) }
            set.note?.let { set(table.note, it) }
        }.execute()
    }
}

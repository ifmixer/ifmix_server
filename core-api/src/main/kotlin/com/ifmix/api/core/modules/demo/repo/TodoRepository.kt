package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoProps
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.id
import com.ifmix.api.core.entity.demo.title
import com.ifmix.api.core.entity.demo.done
import com.ifmix.api.core.entity.demo.note
import com.ifmix.api.core.entity.demo.userId
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.TodoUnsetField
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

    fun findById(mc: ModuleCtx, appId: UUID, id: UUID): Todo? = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): List<Todo> = tpl.findByIds(mc, appId, ids)
    fun save(mc: ModuleCtx, entity: Todo) = tpl.save(mc, entity)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun deleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(mc, appId, ids)

    fun findByCursor(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> {
        return tpl.findByCursor(mc, appId, cursor, limit) {
            filter?.done?.let { where(table.done eq it) }
            filter?.userId?.let { where(table.userId eq it) }
        }
    }

    /**
     * 基于 FilterGroup + cursor 的动态查询。
     */
    fun findByFilter(mc: ModuleCtx, appId: UUID, filter: FilterGroup?, cursor: UUID?, limit: Int): Page<Todo> {
        val rows = mc.sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            FilterGroupResolver.apply(this, filter, FILTERABLE)
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(rows, limit) { it.id.toString() }
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoInput): Int {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()

        // 没有任何更新请求
        if (set == null && unset.isEmpty()) return 0

        return mc.sql.createUpdate(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq input.id)

            // unset 优先：如果字段同时出现在 set 和 unset，以 unset 为准
            if (TodoUnsetField.NOTE in unset) {
                set(table.note, null as String?)
            } else {
                set?.note?.let { set(table.note, it) }
            }

            if (TodoUnsetField.NOTE !in unset) {
                set?.title?.let { set(table.title, it) }
            }

            if (TodoUnsetField.NOTE !in unset) {
                set?.done?.let { set(table.done, it) }
            }
        }.execute()
    }
}

package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreTodoItem.Companion.CORE_TODO_ITEM
import com.ifmix.api.core.model.TodoItem
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoItemRepository(private val crud: CrudOps) {

    fun batchInsert(ctx: RepoContext, items: List<TodoItem>) =
        crud.batchInsertTyped(ctx, CORE_TODO_ITEM, items)

    fun batchUpdate(ctx: RepoContext, appId: UUID, updates: List<UpdateTodoItemInput>) {
        if (updates.isEmpty()) return
        val batch = updates.map { u ->
            val stmt = ctx.dsl.update(CORE_TODO_ITEM)
                .set(CORE_TODO_ITEM.UPDATED_AT, Instant.now())
            u.set?.content?.let { stmt.set(CORE_TODO_ITEM.CONTENT, it) }
            u.set?.done?.let { stmt.set(CORE_TODO_ITEM.DONE, it) }
            // ponytail: NOTE 列 migration 后取消注释
            // u.set?.note?.let { stmt.set(CORE_TODO_ITEM.NOTE, it) }
            // if (u.unset?.contains(TodoItemUnsetField.NOTE) == true) { stmt.setNull(CORE_TODO_ITEM.NOTE) }
            stmt.where(CORE_TODO_ITEM.ID.eq(u.id).and(CORE_TODO_ITEM.APP_ID.eq(appId)))
        }
        ctx.dsl.batch(batch).execute()
    }

    fun findByTodoIds(ctx: RepoContext, todoIds: Collection<UUID>): List<TodoItem> =
        crud.findByField(ctx, CORE_TODO_ITEM, CORE_TODO_ITEM.TODO_ID, todoIds, TodoItem::class.java, CORE_TODO_ITEM.DELETED_AT)

    fun deleteByIds(ctx: RepoContext, appId: UUID, ids: Collection<UUID>): Int =
        crud.deleteByIds(ctx, CORE_TODO_ITEM, CORE_TODO_ITEM.APP_ID, CORE_TODO_ITEM.ID, appId, ids, CORE_TODO_ITEM.DELETED_AT)
}

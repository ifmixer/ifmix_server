package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreTodoItem.Companion.CORE_demo_ITEM
import com.ifmix.api.core.entity.demo.TodoItem
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoItemRepository(private val crud: CrudRepoOps) {

    fun batchInsert(ctx: SvcCtx, items: List<TodoItem>) =
        crud.batchInsertTyped(ctx, CORE_demo_ITEM, items)

    fun batchUpdate(ctx: SvcCtx, appId: UUID, updates: List<UpdateTodoItemInput>) {
        if (updates.isEmpty()) return
        val batch = updates.map { u ->
            val stmt = ctx.dsl.update(CORE_demo_ITEM)
                .set(CORE_demo_ITEM.UPDATED_AT, Instant.now())
            u.set?.content?.let { stmt.set(CORE_demo_ITEM.CONTENT, it) }
            u.set?.done?.let { stmt.set(CORE_demo_ITEM.DONE, it) }
            // ponytail: NOTE 列 migration 后取消注释
            // u.set?.note?.let { stmt.set(CORE_demo_ITEM.NOTE, it) }
            // if (u.unset?.contains(TodoItemUnsetField.NOTE) == true) { stmt.setNull(CORE_demo_ITEM.NOTE) }
            stmt.where(CORE_demo_ITEM.ID.eq(u.id).and(CORE_demo_ITEM.APP_ID.eq(appId)))
        }
        ctx.dsl.batch(batch).execute()
    }

    fun findByTodoIds(ctx: SvcCtx, todoIds: Collection<UUID>): List<TodoItem> =
        crud.findByField(ctx, CORE_demo_ITEM, CORE_demo_ITEM.TODO_ID, todoIds, TodoItem::class.java, CORE_demo_ITEM.DELETED_AT)

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int =
        crud.deleteByIds(ctx, CORE_demo_ITEM, CORE_demo_ITEM.APP_ID, CORE_demo_ITEM.ID, appId, ids, CORE_demo_ITEM.DELETED_AT)
}

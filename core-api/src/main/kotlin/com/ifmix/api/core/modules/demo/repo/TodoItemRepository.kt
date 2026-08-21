package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreTodoItem.Companion.CORE_TODO_ITEM
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoItemRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            TodoItem::id.name to CORE_TODO_ITEM.ID,
            TodoItem::appId.name to CORE_TODO_ITEM.APP_ID,
            TodoItem::todoId.name to CORE_TODO_ITEM.TODO_ID,
            TodoItem::content.name to CORE_TODO_ITEM.CONTENT,
            TodoItem::done.name to CORE_TODO_ITEM.DONE,
            TodoItem::createdAt.name to CORE_TODO_ITEM.CREATED_AT,
            TodoItem::updatedAt.name to CORE_TODO_ITEM.UPDATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_TODO_ITEM,
        idField = CORE_TODO_ITEM.ID,
        appIdField = CORE_TODO_ITEM.APP_ID,
        type = TodoItem::class.java,
        deletedAtField = CORE_TODO_ITEM.DELETED_AT,
    )

    fun insert(ctx: SvcCtx, item: TodoItem) = crud.insert(ctx, item)

    fun batchInsert(ctx: SvcCtx, items: List<TodoItem>) = crud.batchInsert(ctx, items)

    fun findByTodoId(ctx: SvcCtx, appId: UUID, todoId: UUID): List<TodoItem> {
        return ctx.dsl.selectFrom(CORE_TODO_ITEM)
            .where(CORE_TODO_ITEM.APP_ID.eq(appId))
            .and(CORE_TODO_ITEM.TODO_ID.eq(todoId))
            .and(CORE_TODO_ITEM.DELETED_AT.isNull)
            .orderBy(CORE_TODO_ITEM.ID.asc())
            .fetchInto(TodoItem::class.java)
    }

    fun findByTodoIds(ctx: SvcCtx, appId: UUID, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return ctx.dsl.selectFrom(CORE_TODO_ITEM)
            .where(CORE_TODO_ITEM.APP_ID.eq(appId))
            .and(CORE_TODO_ITEM.TODO_ID.`in`(todoIds))
            .and(CORE_TODO_ITEM.DELETED_AT.isNull)
            .orderBy(CORE_TODO_ITEM.ID.asc())
            .fetchInto(TodoItem::class.java)
    }

    /** DataLoader 批量查询：todoId 已是 UUIDv7 全局唯一，无需 appId 过滤。 */
    fun findByTodoIds(ctx: SvcCtx, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return ctx.dsl.selectFrom(CORE_TODO_ITEM)
            .where(CORE_TODO_ITEM.TODO_ID.`in`(todoIds))
            .and(CORE_TODO_ITEM.DELETED_AT.isNull)
            .orderBy(CORE_TODO_ITEM.ID.asc())
            .fetchInto(TodoItem::class.java)
    }

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): TodoItem? = crud.findById(ctx, appId, id)

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean = crud.deleteById(ctx, appId, id)

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int = crud.deleteByIds(ctx, appId, ids)

    fun deleteByTodoId(ctx: SvcCtx, appId: UUID, todoId: UUID): Int {
        return ctx.dsl.update(CORE_TODO_ITEM)
            .set(CORE_TODO_ITEM.DELETED_AT, Instant.now())
            .where(CORE_TODO_ITEM.APP_ID.eq(appId))
            .and(CORE_TODO_ITEM.TODO_ID.eq(todoId))
            .and(CORE_TODO_ITEM.DELETED_AT.isNull)
            .execute()
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, block: org.jooq.UpdateSetMoreStep<org.jooq.Record>.() -> Unit) =
        crud.partialUpdate(ctx, appId, id, block)
}

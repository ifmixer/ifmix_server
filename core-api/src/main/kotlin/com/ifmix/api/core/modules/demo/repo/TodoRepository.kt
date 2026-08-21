package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoWithStats
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreTodo.Companion.CORE_TODO
import com.ifmix.api.core.jooq.tables.CoreTodoItem.Companion.CORE_TODO_ITEM
import org.jooq.TableField
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            Todo::id.name to CORE_TODO.ID,
            Todo::appId.name to CORE_TODO.APP_ID,
            Todo::installId.name to CORE_TODO.INSTALL_ID,
            Todo::userId.name to CORE_TODO.USER_ID,
            Todo::title.name to CORE_TODO.TITLE,
            Todo::done.name to CORE_TODO.DONE,
            Todo::createdAt.name to CORE_TODO.CREATED_AT,
            Todo::updatedAt.name to CORE_TODO.UPDATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_TODO,
        idField = CORE_TODO.ID,
        appIdField = CORE_TODO.APP_ID,
        type = Todo::class.java,
        deletedAtField = CORE_TODO.DELETED_AT,
    )

    fun insert(ctx: SvcCtx, todo: Todo) = crud.insert(ctx, todo)

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Todo? = crud.findById(ctx, appId, id)

    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<Todo> = crud.findByIds(ctx, appId, ids)

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): Page<Todo> {
        val items = crud.findByCursor(ctx, appId, cursor, limit + 1)
        return Page.of(items, limit) { it.id.toString() }
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean = crud.deleteById(ctx, appId, id)

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int = crud.deleteByIds(ctx, appId, ids)

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, block: org.jooq.UpdateSetMoreStep<org.jooq.Record>.() -> Unit) =
        crud.partialUpdate(ctx, appId, id, block)

    // ===== JOIN 演示 =====

    /**
     * LEFT JOIN core_todo_item + GROUP BY 示例。
     *
     * SQL 等效:
     * ```sql
     * SELECT t.id, t.title, t.done, t.created_at, t.updated_at,
     *        COUNT(i.id)                                  AS item_count,
     *        COUNT(i.id) FILTER (WHERE i.done = false)    AS pending_item_count
     * FROM core_todo t
     * LEFT JOIN core_todo_item i
     *        ON i.todo_id = t.id AND i.deleted_at IS NULL
     * WHERE t.app_id = ? AND t.deleted_at IS NULL
     *   AND (t.id < ?)   -- cursor
     * GROUP BY t.id, t.title, t.done, t.created_at, t.updated_at
     * ORDER BY t.id DESC
     * LIMIT ?
     * ```
     */
    fun findWithStatsByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): Page<TodoWithStats> {
        val itemCount = DSL.count(CORE_TODO_ITEM.ID).`as`("item_count")
        val pendingItemCount = DSL.count(CORE_TODO_ITEM.ID)
            .filterWhere(CORE_TODO_ITEM.DONE.eq(false))
            .`as`("pending_item_count")

        var cond = CORE_TODO.APP_ID.eq(appId).and(CORE_TODO.DELETED_AT.isNull)
        cursor?.let { cond = cond.and(CORE_TODO.ID.lt(it)) }

        val rows = ctx.dsl
            .select(
                CORE_TODO.ID,
                CORE_TODO.TITLE,
                CORE_TODO.DONE,
                CORE_TODO.CREATED_AT,
                CORE_TODO.UPDATED_AT,
                itemCount,
                pendingItemCount,
            )
            .from(CORE_TODO)
            .leftJoin(CORE_TODO_ITEM)
            .on(
                CORE_TODO_ITEM.TODO_ID.eq(CORE_TODO.ID)
                    .and(CORE_TODO_ITEM.DELETED_AT.isNull)
            )
            .where(cond)
            .groupBy(CORE_TODO.ID, CORE_TODO.TITLE, CORE_TODO.DONE, CORE_TODO.CREATED_AT, CORE_TODO.UPDATED_AT)
            .orderBy(CORE_TODO.ID.desc())
            .limit(limit + 1)
            .fetch { r ->
                TodoWithStats(
                    id = r[CORE_TODO.ID]!!,
                    title = r[CORE_TODO.TITLE]!!,
                    done = r[CORE_TODO.DONE]!!,
                    createdAt = r[CORE_TODO.CREATED_AT]!!,
                    updatedAt = r[CORE_TODO.UPDATED_AT],
                    itemCount = r[itemCount] ?: 0,
                    pendingItemCount = r[pendingItemCount] ?: 0,
                )
            }

        return Page.of(rows, limit) { it.id.toString() }
    }
}

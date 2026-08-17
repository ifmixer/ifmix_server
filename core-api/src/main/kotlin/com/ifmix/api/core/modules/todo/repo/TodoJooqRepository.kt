package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.jooq.BaseCrudRepo
import com.ifmix.api.core.model.Todo
import com.ifmix.api.core.model.TodoItem
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * jOOQ 版 TodoRepository。
 *
 * 临时方案：因 codegen 尚未运行，使用 DSL.table/DSL.field 字符串引用。
 * 后续 codegen 完成后替换为类型安全的 Table/Field 引用。
 */
@Repository("todoJooqRepository")
class TodoJooqRepository(dsl: DSLContext) : BaseCrudRepo<Todo>(
    dsl = dsl,
    table = TABLE,
    idField = ID,
    appIdField = APP_ID,
    modelClass = Todo::class.java,
    deletedAtField = DELETED_AT,
) {

    // ===== Write: Todo =====

    /**
     * 插入一条 Todo 记录。
     * id 可由调用方指定（幂等场景），不传则自动生成 UUIDv7。
     */
    fun insert(
        appId: UUID,
        id: UUID = UuidV7.generate(),
        installId: UUID?,
        userId: UUID?,
        title: String,
        done: Boolean = false,
        note: String? = null,
        meta: String? = null, // JSON string, stored as jsonb
    ) {
        dsl.insertInto(TABLE)
            .set(ID, id)
            .set(APP_ID, appId)
            .set(INSTALL_ID, installId)
            .set(USER_ID, userId)
            .set(TITLE, title)
            .set(DONE, done)
            .set(NOTE, note)
            .set(META, meta)
            .set(CREATED_AT, Instant.now())
            .set(UPDATED_AT, Instant.now())
            .execute()
    }

    /**
     * partial update — 只 SET 有值的字段，支持 unset（置 NULL）。
     */
    fun partialUpdate(appId: UUID, input: UpdateTodoInput) {
        val update = dsl.update(TABLE)
            .set(UPDATED_AT, Instant.now())

        input.set?.title?.let { update.set(TITLE, it) }
        input.set?.done?.let { update.set(DONE, it) }
        input.set?.note?.let { update.set(NOTE, it) }
        if (input.unset?.contains(TodoUnsetField.NOTE) == true) {
            update.setNull(NOTE)
        }

        update.where(ID.eq(input.id).and(APP_ID.eq(appId)))
            .execute()
    }

    // ===== Write: TodoItem =====

    /**
     * 批量插入 items。
     */
    fun saveItems(appId: UUID, items: List<TodoItem>) {
        if (items.isEmpty()) return
        val insert = dsl.insertInto(
            ITEM_TABLE,
            ITEM_ID, ITEM_APP_ID, ITEM_TODO_ID,
            ITEM_CONTENT, ITEM_DONE, ITEM_NOTE,
            ITEM_CREATED_AT, ITEM_UPDATED_AT,
        )
        val now = Instant.now()
        items.forEach { item ->
            insert.values(
                item.id, appId, item.todoId,
                item.content, item.done, item.note,
                now, now,
            )
        }
        insert.execute()
    }

    /**
     * 批量 partial update items，支持 set/unset。
     */
    fun updateItems(appId: UUID, updates: List<UpdateTodoItemInput>) {
        if (updates.isEmpty()) return
        val batch = updates.map { u ->
            val stmt = dsl.update(ITEM_TABLE)
                .set(ITEM_UPDATED_AT, Instant.now())

            u.set?.content?.let { stmt.set(ITEM_CONTENT, it) }
            u.set?.done?.let { stmt.set(ITEM_DONE, it) }
            u.set?.note?.let { stmt.set(ITEM_NOTE, it) }
            if (u.unset?.contains(TodoItemUnsetField.NOTE) == true) {
                stmt.setNull(ITEM_NOTE)
            }

            stmt.where(ITEM_ID.eq(u.id).and(ITEM_APP_ID.eq(appId)))
        }
        dsl.batch(batch).execute()
    }

    // ===== Query: TodoItem =====

    /**
     * DataLoader 用 — 批量查 items by todoIds。
     */
    fun findItemsByTodoIds(todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return dsl.selectFrom(ITEM_TABLE)
            .where(ITEM_TODO_ID.`in`(todoIds).and(ITEM_DELETED_AT.isNull))
            .fetchInto(TodoItem::class.java)
    }

    // ===== Delete: TodoItem =====

    /**
     * 软删除 items by ids。
     */
    fun deleteItemsByIds(appId: UUID, ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        return dsl.update(ITEM_TABLE)
            .set(ITEM_DELETED_AT, Instant.now())
            .where(ITEM_ID.`in`(ids).and(ITEM_APP_ID.eq(appId)))
            .execute()
    }

    companion object {
        // ponytail: 临时字符串 DSL — codegen 跑完后替换为 CoreTodo.CORE_TODO.* 类型安全引用

        // --- core_todo ---
        private val TABLE = DSL.table("core_todo")
        private val ID: Field<UUID> = DSL.field("id", UUID::class.java)
        private val APP_ID: Field<UUID> = DSL.field("app_id", UUID::class.java)
        private val INSTALL_ID: Field<UUID?> = DSL.field("install_id", UUID::class.java)
        private val USER_ID: Field<UUID?> = DSL.field("user_id", UUID::class.java)
        private val TITLE: Field<String> = DSL.field("title", String::class.java)
        private val DONE: Field<Boolean> = DSL.field("done", Boolean::class.java)
        private val NOTE: Field<String?> = DSL.field("note", String::class.java)
        private val META: Field<String?> = DSL.field("meta", String::class.java)
        private val CREATED_AT: Field<Instant> = DSL.field("created_at", Instant::class.java)
        private val UPDATED_AT: Field<Instant?> = DSL.field("updated_at", Instant::class.java)
        private val DELETED_AT: Field<Instant?> = DSL.field("deleted_at", Instant::class.java)

        // --- core_todo_item ---
        private val ITEM_TABLE = DSL.table("core_todo_item")
        private val ITEM_ID: Field<UUID> = DSL.field("id", UUID::class.java)
        private val ITEM_APP_ID: Field<UUID> = DSL.field("app_id", UUID::class.java)
        private val ITEM_TODO_ID: Field<UUID> = DSL.field("todo_id", UUID::class.java)
        private val ITEM_CONTENT: Field<String> = DSL.field("content", String::class.java)
        private val ITEM_DONE: Field<Boolean> = DSL.field("done", Boolean::class.java)
        private val ITEM_NOTE: Field<String?> = DSL.field("note", String::class.java)
        private val ITEM_CREATED_AT: Field<Instant> = DSL.field("created_at", Instant::class.java)
        private val ITEM_UPDATED_AT: Field<Instant?> = DSL.field("updated_at", Instant::class.java)
        private val ITEM_DELETED_AT: Field<Instant?> = DSL.field("deleted_at", Instant::class.java)
    }
}

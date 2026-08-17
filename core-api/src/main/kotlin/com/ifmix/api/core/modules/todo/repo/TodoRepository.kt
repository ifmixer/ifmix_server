package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.by
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.entity.todo.todoId
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository(private val sql: KSqlClient) {

    // ===== Query =====

    fun findById(appId: UUID, id: UUID): Todo? {
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            select(table.fetch(ALL_SCALAR))
        }.limit(1).execute().firstOrNull()
    }

    fun findByIds(appId: UUID, ids: List<UUID>): List<Todo> {
        if (ids.isEmpty()) return emptyList()
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id valueIn ids)
            select(table.fetch(ALL_SCALAR))
        }.execute()
    }

    fun findByCursor(appId: UUID, cursor: UUID?, limit: Int): List<Todo> {
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            if (cursor != null) {
                where(table.id lt cursor)
            }
            orderBy(table.id.desc())
            select(table.fetch(ALL_SCALAR))
        }.limit(limit).execute()
    }

    fun exists(appId: UUID, id: UUID): Boolean {
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            select(table.id)
        }.limit(1).execute().isNotEmpty()
    }

    // ===== TodoItem Query =====

    fun findItemsByTodoIds(todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return sql.createQuery(TodoItem::class) {
            where(table.todoId valueIn todoIds)
            select(table.fetch(ITEM_WITH_TODO_ID))
        }.execute()
    }

    fun findItemById(appId: UUID, itemId: UUID): TodoItem? {
        return sql.createQuery(TodoItem::class) {
            where(table.id eq itemId)
            where(table.appId eq appId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    // ===== Write =====

    fun save(entity: Todo) {
        sql.entities.save(entity)
    }

    fun insert(entity: Todo) {
        sql.entities.save(entity) {
            setMode(org.babyfish.jimmer.sql.ast.mutation.SaveMode.INSERT_ONLY)
        }
    }

    fun saveItem(entity: TodoItem) {
        sql.entities.save(entity)
    }

    fun insertItem(entity: TodoItem) {
        sql.entities.save(entity) {
            setMode(org.babyfish.jimmer.sql.ast.mutation.SaveMode.INSERT_ONLY)
        }
    }

    fun saveItems(entities: List<TodoItem>) {
        if (entities.isEmpty()) return
        sql.entities.saveEntities(entities)
    }

    // ===== Delete =====

    fun deleteTodo(appId: UUID, id: UUID): Boolean {
        val count = sql.createDelete(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
        }.execute()
        return count > 0
    }

    fun deleteTodosByIds(appId: UUID, ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        return sql.createDelete(Todo::class) {
            where(table.appId eq appId)
            where(table.id valueIn ids)
        }.execute()
    }

    fun deleteItemsByIds(appId: UUID, itemIds: List<UUID>): Int {
        if (itemIds.isEmpty()) return 0
        return sql.createDelete(TodoItem::class) {
            where(table.id valueIn itemIds)
            where(table.appId eq appId)
        }.execute()
    }

    // ===== Fetchers =====

    companion object {
        val ALL_SCALAR: Fetcher<Todo> = newFetcher(Todo::class).by {
            allScalarFields()
        }

        val ITEM_WITH_TODO_ID: Fetcher<TodoItem> = newFetcher(TodoItem::class).by {
            allScalarFields()
            todo()  // id-only, 用于 groupBy
        }
    }
}

package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoDetailDto
import com.ifmix.api.core.entity.todo.dto.TodoListDto
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository(sql: KSqlClient) : BaseAppCrudRepository<Todo>(sql, Todo::class) {

    fun findTodoById(ctx: RepoContext, appId: UUID, id: UUID): TodoDetailDto? {
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            select(table.fetch(TodoDetailDto::class))
        }.limit(1).execute().firstOrNull()
    }

    fun create(ctx: RepoContext, appId: UUID, installId: UUID?, userId: UUID?, input: TodoCreateInput): UUID {
        val entity = input.toEntity {
            id = UuidV7.generate()
            this.appId = appId
            this.installId = installId
            this.userId = userId
            items().forEach {
                it.id = UuidV7.generate()
                it.appId = appId
            }
        }
        return sql.entities.save(entity) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity.id
    }

    fun update(ctx: RepoContext, appId: UUID, input: TodoUpdateInput): Boolean {
        if (!exists(appId, input.id)) return false
        val entity = input.toEntity {
            this.appId = appId
            items()?.forEach {
                if (it.id == null) it.id = UuidV7.generate()
                it.appId = appId
            }
        }
        sql.entities.save(entity) {
            setAssociatedMode(Todo::items, AssociatedSaveMode.MERGE)
        }
        return true
    }

    fun batchUpdate(ctx: RepoContext, appId: UUID, inputs: List<TodoUpdateInput>): Int {
        if (inputs.isEmpty()) return 0
        val entities = inputs.map { input ->
            input.toEntity {
                this.appId = appId
                items()?.forEach {
                    if (it.id == null) it.id = UuidV7.generate()
                    it.appId = appId
                }
            }
        }
        return sql.entities.saveEntities(entities) {
            setAssociatedMode(Todo::items, AssociatedSaveMode.MERGE)
        }.totalAffectedRowCount
    }

    fun deleteTodo(ctx: RepoContext, appId: UUID, id: UUID): Boolean {
        val count = sql.createDelete(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
        }.execute()
        return count > 0
    }

    fun deleteItemsByIds(ctx: RepoContext, appId: UUID, itemIds: List<UUID>): Int {
        if (itemIds.isEmpty()) return 0
        return sql.createDelete(TodoItem::class) {
            where(table.id valueIn itemIds)
            where(table.appId eq appId)
        }.execute()
    }

    fun findTodoByIds(ctx: RepoContext, appId: UUID, ids: List<UUID>): List<TodoListDto> {
        if (ids.isEmpty()) return emptyList()
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id valueIn ids)
            select(table.fetch(TodoListDto::class))
        }.execute()
    }

    fun deleteTodosByIds(ctx: RepoContext, appId: UUID, ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        return sql.createDelete(Todo::class) {
            where(table.appId eq appId)
            where(table.id valueIn ids)
        }.execute()
    }
}

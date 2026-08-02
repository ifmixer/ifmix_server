package com.ifmix.api.core.repository.todo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoView
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Todo 数据访问——纯数据层，不做权限判断。
 * 租户隔离（appId）由父类 + 查询条件保证。
 */
@Repository
class TodoRepository(sql: KSqlClient) : BaseAppCrudRepository<Todo>(sql, Todo::class) {

    /** 按租户 + id 取单条视图。 */
    fun findTodoById(ctx: OperationContext, id: UUID): TodoView? {
        val appId = ctx.mustGetAppId()
        return sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            select(table.fetch(TodoView::class))
        }.limit(1).execute().firstOrNull()
    }

    fun create(ctx: OperationContext, input: TodoCreateInput): Todo {
        val appId = ctx.mustGetAppId()
        val entity = input.toEntity {
            id = UuidV7.generate()
            this.appId = appId
            this.installId = ctx.installId
            this.userId = ctx.userId
            items().forEach {
                it.id = UuidV7.generate()
                it.appId = appId
            }
        }
        return sql.entities.save(entity) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity
    }

    /** 更新；id 不属于当前租户时返回 null。 */
    fun update(ctx: OperationContext, input: TodoUpdateInput): Todo? {
        val appId = ctx.mustGetAppId()
        if (!existsForApp(appId, input.id)) return null
        val entity = input.toEntity {
            this.appId = appId
            items().forEach {
                if (it.id == null) it.id = UuidV7.generate()
                it.appId = appId
            }
        }
        return sql.entities.save(entity) {
            setAssociatedMode(Todo::items, AssociatedSaveMode.MERGE)
        }.modifiedEntity
    }

    /** 删除单条 todo。 */
    fun deleteTodo(ctx: OperationContext, id: UUID): Boolean {
        val appId = ctx.mustGetAppId()
        val count = sql.createDelete(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
        }.execute()
        return count > 0
    }

    /** 批量软删 todo items。 */
    fun deleteItemsByIds(ctx: OperationContext, itemIds: List<UUID>): Int {
        if (itemIds.isEmpty()) return 0
        val appId = ctx.mustGetAppId()
        return sql.createDelete(TodoItem::class) {
            where(table.id valueIn itemIds)
            where(table.appId eq appId)
        }.execute()
    }
}

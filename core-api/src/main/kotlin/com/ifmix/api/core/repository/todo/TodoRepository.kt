package com.ifmix.api.core.repository.todo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.appId
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoView
import com.ifmix.api.core.entity.todo.id
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Todo 数据访问。
 *
 * 查询一律 fetch 成 [TodoView]（Jimmer DTO），避免把实体接口暴露到上层——
 * 实体是 Jimmer 动态对象，未 fetch 的属性访问会抛异常，也无法被 OpenAPI 正确描述。
 */
@Repository
class TodoRepository(sql: KSqlClient) : BaseAppCrudRepository<Todo>(sql, Todo::class) {

    /** 按租户 + id 取单条视图。 */
    fun findTodoById(repoCtx: RepoContext, appId: UUID, id: UUID): TodoView? =
        sql.createQuery(Todo::class) {
            where(table.appId eq appId, table.id eq id)
            select(table.fetch(TodoView::class))
        }.limit(1).execute().firstOrNull()

    fun create(repoCtx: RepoContext, appId: UUID, input: TodoCreateInput): Todo {
        val now = Instant.now()
        val entity = Todo {
            id = UuidV7.generate()
            this.appId = appId
            title = input.title
            done = input.done
            createdAt = now
            updatedAt = now
        }
        return sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }.modifiedEntity
    }

    /** 更新；id 不属于当前租户时返回 null（不泄漏其他租户的存在性）。 */
    fun update(repoCtx: RepoContext, appId: UUID, input: TodoUpdateInput): Todo? {
        if (!existsForApp(appId, input.id)) return null
        val entity = Todo {
            id = input.id
            this.appId = appId
            title = input.title
            done = input.done
            updatedAt = Instant.now()
        }
        return sql.entities.save(entity) { setMode(SaveMode.UPDATE_ONLY) }.modifiedEntity
    }

    /** 软删（实体标了 @LogicalDeleted）。返回 false 表示该 id 不属于当前租户。 */
    fun deleteForApp(repoCtx: RepoContext, appId: UUID, id: UUID): Boolean {
        if (!existsForApp(appId, id)) return false
        deleteById(repo, id)
        return true
    }

    private fun existsForApp(appId: UUID, id: UUID): Boolean =
        sql.createQuery(Todo::class) {
            where(table.appId eq appId, table.id eq id)
            select(table.id)
        }.limit(1).execute().isNotEmpty()
}

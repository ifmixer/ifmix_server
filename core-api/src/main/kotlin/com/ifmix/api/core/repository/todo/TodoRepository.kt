package com.ifmix.api.core.repository.todo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
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
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
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
        val entity = input.toEntity {
            id = UuidV7.generate()
            this.appId = appId
            items().forEach {
                it.id = UuidV7.generate()
                it.appId = appId
            }
        }
        return sql.entities.save(entity) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity
    }

    /** 更新；id 不属于当前租户时返回 null（不泄漏其他租户的存在性）。 */
    fun update(repoCtx: RepoContext, appId: UUID, input: TodoUpdateInput): Todo? {
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

    /** 批量软删 todo items。仅删除属于指定 appId 的 items。 */
    fun deleteItemsByIds(repoCtx: RepoContext, appId: UUID, itemIds: List<UUID>): Int {
        if (itemIds.isEmpty()) return 0
        return sql.createDelete(TodoItem::class) {
            where(table.getId<UUID>() valueIn itemIds)
            where(table.get<UUID>("appId") eq appId)
        }.execute()
    }
}

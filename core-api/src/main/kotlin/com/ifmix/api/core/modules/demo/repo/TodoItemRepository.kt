package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.todoId
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.id

@Repository
class TodoItemRepository(sql: KSqlClient) : BaseAppCrudRepository<TodoItem>(sql, TodoItem::class) {

    fun findByTodoIds(ctx: SvcCtx, appId: UUID, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return ctx.sql.createQuery(TodoItem::class) {
            where(table.appId eq appId)
            where(table.get<UUID>("todoId") valueIn todoIds)
            select(table)
        }.execute()
    }
}

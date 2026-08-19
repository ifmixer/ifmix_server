package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.todoId
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.id
import org.babyfish.jimmer.sql.kt.ast.table.table

@Repository
class TodoItemRepository(sql: KSqlClient) : BaseCrudRepository<TodoItem>(sql, TodoItem::class) {

    fun findByTodoIds(ctx: SvcCtx, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return ctx.sql.createQuery(TodoItem::class) {
            where(table.get<UUID>("todoId") valueIn todoIds)
            select(table)
        }.execute()
    }
}

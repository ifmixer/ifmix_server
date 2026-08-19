package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.id

@Repository
class TodoRepository(sql: KSqlClient) : BaseAppCrudRepository<Todo>(sql, Todo::class) {

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<Todo> {
        return ctx.sql.createQuery(Todo::class) {
            where(table.get<UUID>("appId") eq appId)
            cursor?.let { where(table.getId<UUID>() lt it) }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit).execute()
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, title: String?, done: Boolean?) {
        // ponytail: Jimmer createUpdate has type inference issues with string-based props
        // Using raw SQL as fallback
        if (title == null && done == null) return
        val setClauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (title != null) {
            setClauses.add("title = ?")
            params.add(title)
        }
        if (done != null) {
            setClauses.add("done = ?")
            params.add(done)
        }
        params.add(appId)
        params.add(id)
        ctx.sql.execute("UPDATE core_todo SET ${setClauses.joinToString(", ")} WHERE app_id = ? AND id = ?", *params.toTypedArray())
    }
}

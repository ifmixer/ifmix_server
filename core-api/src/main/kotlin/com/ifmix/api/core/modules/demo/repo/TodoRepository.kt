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
import com.ifmix.api.core.entity.demo.title
import com.ifmix.api.core.entity.demo.done

@Repository
class TodoRepository(sql: KSqlClient) : BaseAppCrudRepository<Todo>(sql, Todo::class) {

    override fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<Todo> {
        return ctx.sql.createQuery(Todo::class) {
            where(table.appId eq appId)
            cursor?.let { where(table.getId<UUID>() lt it) }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit).execute()
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, title: String?, done: Boolean?) {
        if (title == null && done == null) return
        ctx.sql.createUpdate(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            title?.let { set(table.title, it) }
            done?.let { set(table.done, it) }
        }.execute()
    }
}

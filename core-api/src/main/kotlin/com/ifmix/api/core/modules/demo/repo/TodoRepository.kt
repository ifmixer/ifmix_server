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
        ctx.sql.createUpdate(Todo::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            title?.let { set(table.get<String>("title"), it) }
            done?.let { set(table.get<Boolean>("done"), it) }
        }.execute()
    }
}

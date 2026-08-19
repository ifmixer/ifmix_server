package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.modules.demo.mybatis.CoreTodoDynamicSqlSupport
import com.ifmix.api.core.entity.demo.Todo
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Todo 数据访问层 — 使用 MyBatis 参数化 SQL。
 */
@Repository
class TodoRepository {

    private val cols = CoreTodoDynamicSqlSupport

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Todo? {
        val result = ctx.mapper<TodoMapper>().selectOneByAppAndId(appId, id) ?: return null
        return toTodo(result)
    }

    fun findByIds(ctx: SvcCtx, ids: Collection<UUID>): List<Todo> {
        if (ids.isEmpty()) return emptyList()
        return ctx.mapper<TodoMapper>().selectByIds(ctx.mustGetAppId(), ids.toList())
            .mapNotNull { toTodo(it) }
    }

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<Todo> =
        ctx.mapper<TodoMapper>()
            .selectByCursor(appId, cursor, limit)
            .mapNotNull { toTodo(it) }

    fun insert(ctx: SvcCtx, entity: Todo) {
        ctx.mapper<TodoMapper>().insert(mapOf(
            "id" to entity.id,
            "appId" to entity.appId,
            "installId" to entity.installId,
            "userId" to entity.userId,
            "title" to entity.title,
            "done" to entity.done,
            "createdAt" to entity.createdAt,
            "updatedAt" to entity.updatedAt,
        ))
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        ctx.mapper<TodoMapper>().deleteById(id, appId) > 0

    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        ctx.mapper<TodoMapper>().exists(appId, id)

    private fun toTodo(m: Map<String, Any?>): Todo? {
        @Suppress("UNCHECKED_CAST")
        return Todo(
            id = m[cols.id.name()] as? UUID ?: return null,
            appId = m[cols.appId.name()] as? UUID ?: return null,
            installId = m[cols.installId.name()] as? UUID,
            userId = m[cols.userId.name()] as? UUID,
            title = m[cols.title.name()] as? String ?: return null,
            done = m[cols.done.name()] as? Boolean ?: false,
            createdAt = m[cols.createdAt.name()] as? Instant ?: return null,
            updatedAt = m[cols.updatedAt.name()] as? Instant,
        )
    }
}

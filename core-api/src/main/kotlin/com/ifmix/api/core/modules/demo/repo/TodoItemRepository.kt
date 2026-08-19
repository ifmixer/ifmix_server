package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.modules.demo.mybatis.CoreTodoItemDynamicSqlSupport
import com.ifmix.api.core.entity.demo.TodoItem
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * TodoItem 数据访问层 — 使用 MyBatis 参数化 SQL。
 */
@Repository
class TodoItemRepository {

    private val todoItemCols = CoreTodoItemDynamicSqlSupport

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): TodoItem? {
        val result = ctx.mapper<TodoItemMapper>().selectOne(id) ?: return null
        return toTodoItem(result)
    }

    fun findByTodoId(ctx: SvcCtx, todoId: UUID): List<TodoItem> =
        ctx.mapper<TodoItemMapper>()
            .selectByTodoId(todoId)
            .mapNotNull { toTodoItem(it) }

    fun insert(ctx: SvcCtx, entity: TodoItem) {
        ctx.mapper<TodoItemMapper>().insert(mapOf(
            "id" to entity.id,
            "appId" to entity.appId,
            "todoId" to entity.todoId,
            "content" to entity.content,
            "done" to entity.done,
            "createdAt" to entity.createdAt,
            "updatedAt" to entity.updatedAt,
        ))
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        ctx.mapper<TodoItemMapper>().deleteById(id, appId) > 0

    private fun toTodoItem(m: Map<String, Any?>): TodoItem? {
        @Suppress("UNCHECKED_CAST")
        return TodoItem(
            id = m[CoreTodoItemDynamicSqlSupport.itemId.name()] as? UUID ?: return null,
            appId = m[CoreTodoItemDynamicSqlSupport.itemAppId.name()] as? UUID ?: return null,
            todoId = m[CoreTodoItemDynamicSqlSupport.todoId.name()] as? UUID ?: return null,
            content = m[CoreTodoItemDynamicSqlSupport.content.name()] as? String ?: return null,
            done = m[CoreTodoItemDynamicSqlSupport.itemDone.name()] as? Boolean ?: false,
            createdAt = m[CoreTodoItemDynamicSqlSupport.itemCreatedAt.name()] as? Instant ?: return null,
            updatedAt = m[CoreTodoItemDynamicSqlSupport.itemUpdatedAt.name()] as? Instant,
        )
    }
}

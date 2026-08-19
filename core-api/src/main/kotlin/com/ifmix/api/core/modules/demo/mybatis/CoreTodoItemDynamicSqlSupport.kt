package com.ifmix.api.core.modules.demo.mybatis

import org.mybatis.dynamic.sql.SqlColumn
import org.mybatis.dynamic.sql.AliasableSqlTable
import java.time.Instant
import java.util.UUID

/**
 * MyBatis Dynamic SQL 表定义 — core_todo_item 表。
 */
object CoreTodoItemDynamicSqlSupport {

    class CoreTodoItem : AliasableSqlTable<CoreTodoItem>("core_todo_item", ::CoreTodoItem) {
        val id: SqlColumn<UUID> = column("id")
        val appId: SqlColumn<UUID> = column("app_id")
        val todoId: SqlColumn<UUID> = column("todo_id")
        val content: SqlColumn<String> = column("content")
        val done: SqlColumn<Boolean> = column("done")
        val createdAt: SqlColumn<Instant> = column("created_at")
        val updatedAt: SqlColumn<Instant> = column("updated_at")
        val deletedAt: SqlColumn<Instant> = column("deleted_at")
    }

    // 顶层静态引用
    val coreTodoItem = CoreTodoItem()
    val itemId = coreTodoItem.id
    val itemAppId = coreTodoItem.appId
    val todoId = coreTodoItem.todoId
    val content = coreTodoItem.content
    val itemDone = coreTodoItem.done
    val itemCreatedAt = coreTodoItem.createdAt
    val itemUpdatedAt = coreTodoItem.updatedAt
    val itemDeletedAt = coreTodoItem.deletedAt
}

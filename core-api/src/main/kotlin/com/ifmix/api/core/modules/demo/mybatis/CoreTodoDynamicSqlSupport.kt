package com.ifmix.api.core.modules.demo.mybatis

import org.mybatis.dynamic.sql.SqlColumn
import org.mybatis.dynamic.sql.AliasableSqlTable
import java.time.Instant
import java.util.UUID

/**
 * MyBatis Dynamic SQL 表定义 — core_todo 表。
 * 列定义与 jOOQ 生成代码保持对应，便于后续迁移参考。
 */
object CoreTodoDynamicSqlSupport {

    class CoreTodo : AliasableSqlTable<CoreTodo>("core_todo", ::CoreTodo) {
        val id: SqlColumn<UUID> = column("id")
        val appId: SqlColumn<UUID> = column("app_id")
        val installId: SqlColumn<UUID> = column("install_id")
        val userId: SqlColumn<UUID> = column("user_id")
        val title: SqlColumn<String> = column("title")
        val done: SqlColumn<Boolean> = column("done")
        val meta: SqlColumn<String> = column("meta")
        val note: SqlColumn<String> = column("note")
        val createdAt: SqlColumn<Instant> = column("created_at")
        val updatedAt: SqlColumn<Instant> = column("updated_at")
        val deletedAt: SqlColumn<Instant> = column("deleted_at")
    }

    // 顶层静态引用，方便使用
    val coreTodo = CoreTodo()
    val id = coreTodo.id
    val appId = coreTodo.appId
    val installId = coreTodo.installId
    val userId = coreTodo.userId
    val title = coreTodo.title
    val done = coreTodo.done
    val meta = coreTodo.meta
    val note = coreTodo.note
    val createdAt = coreTodo.createdAt
    val updatedAt = coreTodo.updatedAt
    val deletedAt = coreTodo.deletedAt
}

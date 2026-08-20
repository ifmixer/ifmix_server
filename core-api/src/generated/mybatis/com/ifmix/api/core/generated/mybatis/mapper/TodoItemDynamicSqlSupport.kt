package com.ifmix.api.core.generated.mybatis.mapper

import java.sql.JDBCType
import java.time.Instant
import java.util.UUID
import org.mybatis.dynamic.sql.AliasableSqlTable
import org.mybatis.dynamic.sql.util.kotlin.elements.column

object TodoItemDynamicSqlSupport {
    val todoItem = TodoItem()

    val id = todoItem.id

    val todoId = todoItem.todoId

    val appId = todoItem.appId

    val content = todoItem.content

    val done = todoItem.done

    val createdAt = todoItem.createdAt

    val updatedAt = todoItem.updatedAt

    val deletedAt = todoItem.deletedAt

    class TodoItem : AliasableSqlTable<TodoItem>("core_todo_item", ::TodoItem) {
        val id = column<UUID>(name = "id", jdbcType = JDBCType.OTHER, javaProperty = "id")

        val todoId = column<UUID>(name = "todo_id", jdbcType = JDBCType.OTHER, javaProperty = "todoId")

        val appId = column<UUID>(name = "app_id", jdbcType = JDBCType.OTHER, javaProperty = "appId")

        val content = column<String>(name = "content", jdbcType = JDBCType.VARCHAR, javaProperty = "content")

        val done = column<Boolean>(name = "done", jdbcType = JDBCType.BIT, javaProperty = "done")

        val createdAt = column<Instant>(name = "created_at", jdbcType = JDBCType.OTHER, javaProperty = "createdAt")

        val updatedAt = column<Instant>(name = "updated_at", jdbcType = JDBCType.OTHER, javaProperty = "updatedAt")

        val deletedAt = column<Instant>(name = "deleted_at", jdbcType = JDBCType.OTHER, javaProperty = "deletedAt")
    }
}
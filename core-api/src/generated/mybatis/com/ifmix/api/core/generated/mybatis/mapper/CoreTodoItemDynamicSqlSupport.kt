package com.ifmix.api.core.generated.mybatis.mapper

import java.sql.JDBCType
import java.time.Instant
import java.util.UUID
import org.mybatis.dynamic.sql.AliasableSqlTable
import org.mybatis.dynamic.sql.util.kotlin.elements.column

object CoreTodoItemDynamicSqlSupport {
    val coreTodoItem = CoreTodoItem()

    val id = coreTodoItem.id

    val todoId = coreTodoItem.todoId

    val appId = coreTodoItem.appId

    val content = coreTodoItem.content

    val done = coreTodoItem.done

    val createdAt = coreTodoItem.createdAt

    val updatedAt = coreTodoItem.updatedAt

    val deletedAt = coreTodoItem.deletedAt

    class CoreTodoItem : AliasableSqlTable<CoreTodoItem>("core_todo_item", ::CoreTodoItem) {
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
package com.ifmix.api.core.generated.mybatis.mapper

import java.sql.JDBCType
import java.time.Instant
import java.util.UUID
import org.mybatis.dynamic.sql.AliasableSqlTable
import org.mybatis.dynamic.sql.util.kotlin.elements.column

object CoreTodoDynamicSqlSupport {
    val coreTodo = CoreTodo()

    val id = coreTodo.id

    val title = coreTodo.title

    val done = coreTodo.done

    val appId = coreTodo.appId

    val createdAt = coreTodo.createdAt

    val updatedAt = coreTodo.updatedAt

    val deletedAt = coreTodo.deletedAt

    val installId = coreTodo.installId

    val userId = coreTodo.userId

    val meta = coreTodo.meta

    val note = coreTodo.note

    class CoreTodo : AliasableSqlTable<CoreTodo>("core_todo", ::CoreTodo) {
        val id = column<UUID>(name = "id", jdbcType = JDBCType.OTHER, javaProperty = "id")

        val title = column<String>(name = "title", jdbcType = JDBCType.VARCHAR, javaProperty = "title")

        val done = column<Boolean>(name = "done", jdbcType = JDBCType.BIT, javaProperty = "done")

        val appId = column<UUID>(name = "app_id", jdbcType = JDBCType.OTHER, javaProperty = "appId")

        val createdAt = column<Instant>(name = "created_at", jdbcType = JDBCType.OTHER, javaProperty = "createdAt")

        val updatedAt = column<Instant>(name = "updated_at", jdbcType = JDBCType.OTHER, javaProperty = "updatedAt")

        val deletedAt = column<Instant>(name = "deleted_at", jdbcType = JDBCType.OTHER, javaProperty = "deletedAt")

        val installId = column<UUID>(name = "install_id", jdbcType = JDBCType.OTHER, javaProperty = "installId")

        val userId = column<UUID>(name = "user_id", jdbcType = JDBCType.OTHER, javaProperty = "userId")

        val meta = column<String>(name = "meta", jdbcType = JDBCType.OTHER, javaProperty = "meta")

        val note = column<String>(name = "note", jdbcType = JDBCType.VARCHAR, javaProperty = "note")
    }
}
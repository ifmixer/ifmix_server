package com.ifmix.api.core.generated.mybatis.mapper

import com.ifmix.api.core.entity.demo.Meta
import java.sql.JDBCType
import java.time.Instant
import java.util.UUID
import org.mybatis.dynamic.sql.AliasableSqlTable
import org.mybatis.dynamic.sql.util.kotlin.elements.column

object TodoDynamicSqlSupport {
    val todo = Todo()

    val id = todo.id

    val title = todo.title

    val done = todo.done

    val appId = todo.appId

    val createdAt = todo.createdAt

    val updatedAt = todo.updatedAt

    val deletedAt = todo.deletedAt

    val installId = todo.installId

    val userId = todo.userId

    val meta = todo.meta

    val note = todo.note

    class Todo : AliasableSqlTable<Todo>("core_todo", ::Todo) {
        val id = column<UUID>(name = "id", jdbcType = JDBCType.OTHER, javaProperty = "id")

        val title = column<String>(name = "title", jdbcType = JDBCType.VARCHAR, javaProperty = "title")

        val done = column<Boolean>(name = "done", jdbcType = JDBCType.BIT, javaProperty = "done")

        val appId = column<UUID>(name = "app_id", jdbcType = JDBCType.OTHER, javaProperty = "appId")

        val createdAt = column<Instant>(name = "created_at", jdbcType = JDBCType.OTHER, javaProperty = "createdAt")

        val updatedAt = column<Instant>(name = "updated_at", jdbcType = JDBCType.OTHER, javaProperty = "updatedAt")

        val deletedAt = column<Instant>(name = "deleted_at", jdbcType = JDBCType.OTHER, javaProperty = "deletedAt")

        val installId = column<UUID>(name = "install_id", jdbcType = JDBCType.OTHER, javaProperty = "installId")

        val userId = column<UUID>(name = "user_id", jdbcType = JDBCType.OTHER, javaProperty = "userId")

        val meta = column<Meta>(name = "meta", jdbcType = JDBCType.OTHER, typeHandler = "com.ifmix.api.core.infra.mybatis.MetaTypeHandler", javaProperty = "meta")

        val note = column<String>(name = "note", jdbcType = JDBCType.VARCHAR, javaProperty = "note")
    }
}
package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.mybatis.TableMeta
import org.mybatis.dynamic.sql.SqlTable
import java.time.Instant
import java.util.UUID

object TodoDynamicSql : SqlTable("core_todo") {
    val id = column<UUID>("id")
    val appId = column<UUID>("app_id")
    val title = column<String>("title")
    val done = column<Boolean>("done")
    val installId = column<UUID>("install_id")
    val userId = column<UUID>("user_id")
    val note = column<String>("note")
    val meta = column<String>("meta")
    val createdAt = column<Instant>("created_at")
    val updatedAt = column<Instant>("updated_at")
    val deletedAt = column<Instant>("deleted_at")

    val allColumns = listOf(id, appId, title, done, installId, userId, note, meta, createdAt, updatedAt, deletedAt)
    val meta_ = TableMeta(table = this, id = id, appId = appId, deletedAt = deletedAt, allColumns = allColumns)
}

object TodoItemDynamicSql : SqlTable("core_demo_item") {
    val id = column<UUID>("id")
    val appId = column<UUID>("app_id")
    val todoId = column<UUID>("todo_id")
    val content = column<String>("content")
    val done = column<Boolean>("done")
    val createdAt = column<Instant>("created_at")
    val updatedAt = column<Instant>("updated_at")
    val deletedAt = column<Instant>("deleted_at")

    val allColumns = listOf(id, appId, todoId, content, done, createdAt, updatedAt, deletedAt)
    val meta_ = TableMeta(table = this, id = id, appId = appId, deletedAt = deletedAt, allColumns = allColumns)
}

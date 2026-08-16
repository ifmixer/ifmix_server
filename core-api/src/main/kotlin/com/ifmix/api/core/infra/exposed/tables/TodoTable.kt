package com.ifmix.api.core.infra.exposed.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

object TodoTable : UUIDTable("core_todo") {
    val appId = uuid("app_id")
    val installId = uuid("install_id").nullable()
    val userId = uuid("user_id").nullable()
    val title = varchar("title", 255)
    val done = bool("done").default(false)
    val meta = jsonb<Map<String, Any?>>(
        "meta",
        serialize = { Json.encodeToString(it) },
        deserialize = { Json.decodeFromString(it) }
    ).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    init {
        index(false, appId, id)
        index(false, appId, userId)
        index(false, appId, installId)
    }
}

object TodoItemTable : UUIDTable("core_todo_item") {
    val appId = uuid("app_id")
    val todoId = uuid("todo_id")
    val content = varchar("content", 1000)
    val done = bool("done").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    init {
        index(false, appId, id)
        index(false, todoId)
    }
}

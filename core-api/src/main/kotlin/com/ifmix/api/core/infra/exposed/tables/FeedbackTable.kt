package com.ifmix.api.core.infra.exposed.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant
import java.util.UUID

object FeedbackTable : UUIDTable("core_feedback") {
    val appId = uuid("app_id")
    val installId = uuid("install_id")
    val userId = uuid("user_id").nullable()
    val scanRecordId = uuid("scan_record_id").nullable()
    val category = short("category")
    val comment = varchar("comment", 1000).nullable()
    val createdAt = timestamp("created_at")

    init {
        index(false, appId, createdAt)
    }
}

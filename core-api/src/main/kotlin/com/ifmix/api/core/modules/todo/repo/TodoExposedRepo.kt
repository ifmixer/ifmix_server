package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.exposed.tables.TodoItemTable
import com.ifmix.api.core.infra.exposed.tables.TodoTable
import org.jetbrains.exposed.sql.ResultRow
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Exposed 版本的 Todo Repository（POC）。
 *
 * 通过 `exposed.todo.enabled=true` 开启；默认关闭，Jimmer 版本不受影响。
 * TODO: 待 Exposed 0.61.0 DSL API 文档确认后再实现完整 CRUD。
 */
@Repository
@ConditionalOnProperty(name = ["exposed.todo.enabled"], havingValue = "true", matchIfMissing = false)
class TodoExposedRepo {
    // Placeholder — Exposed 0.61.0 DSL requires further investigation
}

data class TodoRow(
    val id: UUID, val appId: UUID, val installId: UUID?, val userId: UUID?,
    val title: String, val done: Boolean, val meta: Map<String, Any?>?,
    val createdAt: Instant, val updatedAt: Instant,
) {
    companion object {
        fun from(r: ResultRow): TodoRow = TodoRow(
            id = r[TodoTable.id].value,
            appId = r[TodoTable.appId],
            installId = r[TodoTable.installId],
            userId = r[TodoTable.userId],
            title = r[TodoTable.title],
            done = r[TodoTable.done],
            meta = r[TodoTable.meta],
            createdAt = r[TodoTable.createdAt],
            updatedAt = r[TodoTable.updatedAt],
        )
    }
}

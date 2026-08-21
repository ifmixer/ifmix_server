package com.ifmix.api.core.modules.demo.entity

import java.time.Instant
import java.util.UUID

/**
 * Todo record — 数据库行 1:1 映射。
 * Phase 0 临时放这里，全量迁移后替换 entity/demo/Todo（Jimmer interface）。
 */
data class TodoEntity(
    val id: UUID,
    val appId: UUID,
    val title: String,
    val done: Boolean = false,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val note: String? = null,
    val meta: String? = null,  // JSONB 暂存为 String，后续可用 TypeHandler 转领域模型
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)

data class TodoItemEntity(
    val id: UUID,
    val appId: UUID,
    val todoId: UUID,
    val content: String,
    val done: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)

/**
 * LEFT JOIN 扁平结果行 — todo 列 + item 列（nullable，因为 LEFT JOIN 无匹配时为 null）。
 */
data class TodoJoinRow(
    // --- todo ---
    val id: UUID,
    val appId: UUID,
    val title: String,
    val done: Boolean,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    // --- item (nullable) ---
    val itemId: UUID? = null,
    val itemContent: String? = null,
    val itemDone: Boolean? = null,
    val itemCreatedAt: Instant? = null,
    val itemDeletedAt: Instant? = null,
)

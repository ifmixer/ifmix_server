package com.ifmix.api.core.modules.demo.repo.mybatis

import java.time.Instant
import java.util.UUID

/**
 * Todo record — 数据库行 1:1 映射。
 * Phase 0 临时放这里，全量迁移后替换 entity/demo/Todo（Jimmer interface）。
 */
data class TodoRecord(
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

data class TodoItemRecord(
    val id: UUID,
    val appId: UUID,
    val todoId: UUID,
    val content: String,
    val done: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)

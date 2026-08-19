package com.ifmix.api.core.entity.demo

import com.baomidou.mybatisplus.annotation.*
import java.time.Instant
import java.util.UUID

/**
 * TodoItem 实体 — 手写 data class，兼作领域模型与 ORM 映射。
 */
@TableName("core_todo_item")
data class TodoItem(
    @TableId(type = IdType.ASSIGN_UUID)
    val id: UUID,
    val todoId: UUID,
    val appId: UUID,
    val content: String,
    val done: Boolean,
    @TableField(fill = FieldFill.INSERT)
    val createdAt: Instant,
    @TableField(fill = FieldFill.INSERT_UPDATE)
    val updatedAt: Instant,
    @TableLogic(value = "NULL", delval = "NOW()")
    val deletedAt: Instant? = null,
)

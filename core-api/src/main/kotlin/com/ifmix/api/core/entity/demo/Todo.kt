package com.ifmix.api.core.entity.demo

import com.baomidou.mybatisplus.annotation.*
import com.ifmix.api.core.infra.mybatis.MetaTypeHandler
import java.time.Instant
import java.util.UUID

/**
 * Todo 实体 — 手写 data class，兼作领域模型与 ORM 映射。
 * JSONB meta 列通过 MetaTypeHandler 自动序列化/反序列化。
 */
@TableName("core_todo")
data class Todo(
    @TableId(type = IdType.ASSIGN_UUID)
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val title: String,
    val done: Boolean = false,
    val note: String? = null,
    @TableField(typeHandler = MetaTypeHandler::class)
    val meta: Meta? = null,
    @TableField(fill = FieldFill.INSERT)
    val createdAt: Instant,
    @TableField(fill = FieldFill.INSERT_UPDATE)
    val updatedAt: Instant,
    @TableLogic(value = "NULL", delval = "NOW()")
    val deletedAt: Instant? = null,
)

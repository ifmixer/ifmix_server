package com.ifmix.api.core.entity.demo

import java.util.UUID

/**
 * Todo 的元数据。存储为 JSONB，领域层为强类型对象。
 */
data class Meta(
    val creatorType: Int? = null,
    val creatorId: UUID? = null,
)

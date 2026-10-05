package com.ifmix.core.api.entity.demo

import com.ifmix.core.api.dto.demo.TodoRecommendInput
import com.ifmix.core.api.dto.demo.TodoRecItemInput
import java.time.Instant
import java.util.UUID

/** Todo.recommend JSONB 值对象 */
data class TodoRecommend(
    val sectionId: UUID,
    val sectionName: String,
    val viewCount: Int? = null,
    val recItems: List<RecItem>? = null,
) {
    data class RecItem(
        val recId: UUID,
        val title: String? = null,
        val priority: Int,
        val createdAt: Instant,
        val updatedAt: Instant? = null,
    )
}

/** 客户端不提交 createdAt/updatedAt（rpc-rollout 契约）：统一由服务端打戳 [stamp]，同批 recItems 共享同一时间戳。 */
fun TodoRecommendInput.toDomain(stamp: Instant = Instant.now()) = TodoRecommend(
    sectionId = sectionId,
    sectionName = sectionName,
    viewCount = viewCount,
    recItems = recItems?.map { it.toDomain(stamp) },
)

fun TodoRecItemInput.toDomain(stamp: Instant) = TodoRecommend.RecItem(
    recId = recId,
    title = title,
    priority = priority,
    createdAt = stamp,
    updatedAt = null,
)

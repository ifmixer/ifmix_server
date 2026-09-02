package com.ifmix.core.api.entity.demo

import com.ifmix.core.api.generated.types.TodoRecommendInput
import com.ifmix.core.api.generated.types.TodoRecItemInput
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

fun TodoRecommendInput.toDomain() = TodoRecommend(
    sectionId = sectionId,
    sectionName = sectionName,
    viewCount = viewCount,
    recItems = recItems?.map { it.toDomain() },
)

fun TodoRecItemInput.toDomain() = TodoRecommend.RecItem(
    recId = recId,
    title = title,
    priority = priority,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

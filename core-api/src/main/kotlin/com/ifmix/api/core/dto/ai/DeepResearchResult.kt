package com.ifmix.api.core.dto.ai

import com.ifmix.api.core.entity.ai.ImageRef
import java.util.UUID

/**
 * DeepResearch 的外部结果 — 事务外调用 AI 后返回，再由事务内方法持久化。
 */
data class DeepResearchResult(
    val scanRecordId: UUID,
    val appId: UUID,
    /** 已按 position 排序的图片列表，回写 scan_record.images */
    val images: List<ImageRef>,
    val basicResult: Map<String, Any?>?,
    val premiumResult: Map<String, Any?>?,
)

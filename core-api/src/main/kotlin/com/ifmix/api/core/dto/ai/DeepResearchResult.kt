package com.ifmix.api.core.dto.ai

import java.util.UUID

/**
 * DeepResearch 的外部结果 — 事务外调用 AI 后返回，再由事务内方法持久化。
 * images 在 AI 调用之前已单独提交，故此处不再携带。
 */
data class DeepResearchResult(
    val scanRecordId: UUID,
    val appId: UUID,
    val basicResult: Map<String, Any?>?,
    val premiumResult: Map<String, Any?>?,
    val promptVersion: String,
)

package com.ifmix.core.api.dto.ai

import com.ifmix.core.api.generated.types.NewScanImageInput
import java.time.Instant
import java.util.UUID

/**
 * AI scan 的外部结果 — 在事务外调用 AI 后返回，再由事务内方法持久化。
 *
 * 设计目的：将耗时外部 AI 调用从 globalTx 内移出，避免长时间持有 DB 连接。
 */
data class AiScanResult(
    val scanId: UUID,
    val projectId: String,
    val locale: String?,
    val country: String?,
    val currency: String?,
    val clientIp: String?,
    val images: List<NewScanImageInput>,
    val basicResult: Map<String, Any?>,
    val collected: Boolean,
    val promptVersion: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

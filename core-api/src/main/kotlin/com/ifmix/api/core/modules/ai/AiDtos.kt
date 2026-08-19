package com.ifmix.api.core.modules.ai

import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** 创建扫描记录的请求体。 */
data class CreateScanRequest(
    @field:NotBlank(message = "imageUrl is required")
    val imageUrl: String,

    /** 可选：关联的 todo / task ID。 */
    val relatedId: String? = null,
)

/** 扫描记录 DTO（对外响应）。字段与 ScanRecordEntity 一一对应。 */
data class ScanDto(
    val id: String?,
    val scanId: String?,
    val imageUrl: String?,
    val status: String?,
    val resultJson: String?,
    val tier: String?,
    val clientIp: String?,
    val relatedId: String?,
    val userId: String?,
    val installId: String?,
    val collected: Boolean?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

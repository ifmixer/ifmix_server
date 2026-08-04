package com.ifmix.api.core.modules.scan.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * 扫描输入中的单个媒体项。
 * 后续可扩展：category、userHint 等。
 */
data class ScanMediaItem(
    /** 预签名下载 URL（供 AI 模型访问） */
    val imageUrl: String,
    /** MIME 类型（如 image/jpeg, image/png） */
    val mediaType: String,
)

/**
 * ScanRunner 的输入 DTO。
 */
data class ScanInput(
    /** 一张或多张图片 */
    val items: List<ScanMediaItem>,
)

data class NewScanImageInput(
    val imageKey: String,
    val mediaType: String,
)

data class NewScanReq(val images: List<NewScanImageInput>)

data class NewScanRes(
    val id: UUID,
    val result: ScanResult,
)

data class UpdateScanReq(
    @Schema(description = "记录 ID（UUIDv7）")
    val id: UUID,
    @Schema(description = "新名称。不传=不修改；传 null=清空（回退到 result.name 快照）；传字符串=设为用户自定义名称。")
    val name: String? = null,
    @Schema(description = "用户备注。不传=不修改；传 null=清空；传字符串=设为用户备注。")
    val userNotes: String? = null,
)

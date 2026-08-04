package com.ifmix.api.core.modules.scan.dto

import com.ifmix.api.core.modules.scan.ScanResult
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

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

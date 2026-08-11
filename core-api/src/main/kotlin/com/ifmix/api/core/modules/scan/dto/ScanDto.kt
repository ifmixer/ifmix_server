package com.ifmix.api.core.modules.scan.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * 扫描输入中的单个媒体项。
 */
data class ScanMediaItem(
    /** 预签名下载 URL（供 AI 模型通过 URL 访问） */
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
    /** 用户语言偏好（如 zh-Hans, en, ja），来自 x-lang 请求头 */
    val lang: String? = null,
    /** 用户所在国家/地区（如 CN, US, JP），来自 x-country 请求头 */
    val country: String? = null,
    /** 用户货币偏好（如 CNY, USD, JPY），来自 x-currency 请求头 */
    val currency: String? = null,
)

data class NewScanImageInput(
    /** 已上传的对象键（必填） */
    @Schema(description = "已上传文件的 objectKey。")
    val imageKey: String,
    val mediaType: String,
)

data class NewScanReq(val images: List<NewScanImageInput>)

data class UpdateScanReq(
    @Schema(description = "记录 ID（UUIDv7）")
    val id: UUID,
    @Schema(description = "新名称。不传=不修改；传 null=清空（回退到 result.name 快照）；传字符串=设为用户自定义名称。")
    val name: String? = null,
    @Schema(description = "用户备注。不传=不修改；传 null=清空；传字符串=设为用户备注。")
    val userNotes: String? = null,
    @Schema(description = "是否收藏。不传=不修改。")
    val collected: Boolean? = null,
)

/** 扫描记录分页查询请求体 */
data class ScanQueryInput(
    @Schema(description = "上一页返回的 nextCursor，首次请求不传")
    val cursor: String? = null,
    @Schema(description = "每页条数，默认 20，上限 100", minimum = "1", maximum = "100")
    val limit: Int = 20,
    @Schema(description = "按收藏状态过滤。不传=不过滤；true=只看已收藏；false=只看未收藏。")
    val collected: Boolean? = null,
)

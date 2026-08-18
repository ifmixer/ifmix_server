package com.ifmix.api.core.dto.scan

import java.time.LocalDate

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
    /** 用户语言偏好（BCP-47，如 zh-CN, en-US, ja-JP） */
    val lang: String? = null,
    /** 用户所在国家/地区（ISO 3166-1 alpha-2，如 CN, US, JP） */
    val country: String? = null,
    /** 用户货币偏好（ISO 4217，如 CNY, USD, JPY） */
    val currency: String? = null,
    /** 当前日期，用于年代分类阈值计算 */
    val date: LocalDate = LocalDate.now(),
)

package com.ifmix.core.api.dto.ai

import com.ifmix.core.api.entity.ai.ImageCategory
import java.time.LocalDate
import java.util.UUID

/** 扫描类型 — 决定使用哪套系统提示词。 */
enum class ScanType {
    /** 基础扫描：仅返回 basic_result。 */
    BASIC,
    /** 深度研究：返回 basic_result + premium_result。 */
    DEEP_RESEARCH,
}

/**
 * 扫描输入中的单个媒体项。
 */
data class ScanMediaItem(
    /** 预签名下载 URL（供 AI 模型通过 URL 访问） */
    val imageUrl: String,
    /** MIME 类型（如 image/jpeg, image/png） */
    val mediaType: String,
    /**
     * 图片分类（码表见 [com.ifmix.core.api.entity.ai.ImageCategories]）。
     * 卡牌背面承载版本 / 版权 / 标志等关键证据，靠模型自己猜哪张是背面会直接
     * 影响 variant / edition / 真伪判断 —— 所以必须原样带到 user prompt（设计 §8.3）。
     * 为 null（历史数据 / DeepResearch 路径）时不产出视角说明行。
     */
    val category: ImageCategory? = null,
)

/**
 * ScanRunner 的输入 DTO。
 */
data class ScanInput(
    val scanId: UUID,
    /** 一张或多张图片（顺序即数组顺序） */
    val items: List<ScanMediaItem>,
    /** 用户语言偏好（BCP-47，如 zh-CN, en-US, ja-JP） */
    val locale: String? = null,
    /** 用户所在国家/地区（ISO 3166-1 alpha-2，如 CN, US, JP） */
    val country: String? = null,
    /** 用户货币偏好（ISO 4217，如 CNY, USD, JPY） */
    val currency: String? = null,
    /** 当前日期，用于年代分类阈值计算 */
    val date: LocalDate = LocalDate.now(),
    /** 扫描类型 — ScanRunner 据此选择系统提示词 */
    val type: ScanType = ScanType.BASIC,
    /**
     * 发起扫描的 app（core_project_info.id，如 antique / tcgcard）。
     * ScanRunner 用它选该项目的提示词组（设计 §8.1）；为 null 时回落到默认组。
     */
    val projectId: String? = null,
)

package com.ifmix.api.core.modules.ai

/**
 * AI 扫描结果。
 *
 * 所有字段采用 snake_case 序列化，与 AI 模型输出约定一致。
 */
data class ScanResult(
    // ---- 基础标识 ----
    val scanId: String,

    /** 扫描状态：PENDING / PROCESSING / COMPLETED / FAILED。 */
    val status: Status = Status.PENDING,

    // ---- 输入信息 ----
    val imageUrl: String? = null,
    val imageMimeType: String? = null,
    val imageSize: String? = null,

    // ---- 核心识别 ----
    val isAntique: Boolean? = null,
    val name: String? = null,
    val nameEn: String? = null,
    val aliases: List<String> = emptyList(),
    val category: String? = null,
    val subCategory: String? = null,
    val label1: String? = null,
    val label2: String? = null,
    val label3: String? = null,

    // ---- 年代判定 ----
    val dynasty: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val dynastyConfidence: Double? = null,

    // ---- 材质工艺 ----
    val material: String? = null,
    val materials: List<String> = emptyList(),
    val technique: String? = null,
    val techniques: List<String> = emptyList(),

    // ---- 外观特征 ----
    val colors: List<String> = emptyList(),
    val shape: String? = null,
    val texture: String? = null,
    val decorations: List<String> = emptyList(),
    val hasInscription: Boolean? = null,
    val inscription: String? = null,

    // ---- 尺寸重量 ----
    val heightCm: Double? = null,
    val widthCm: Double? = null,
    val depthCm: Double? = null,
    val weightG: Double? = null,

    // ---- 价值评估 ----
    val priceRange: String? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val priceCurrency: String? = null,
    val valueConfidence: Double? = null,

    // ---- 真伪鉴定 ----
    val authenticity: String? = null,
    val authenticityConfidence: Double? = null,
    val authenticityNotes: String? = null,

    // ---- 保存状况 ----
    val condition: String? = null,
    val flaws: List<String> = emptyList(),
    val restorationHistory: String? = null,

    // ---- 综合评分 ----
    val score: Int? = null,
    val confidence: Double? = null,

    // ---- AI 模型信息 ----
    val modelName: String? = null,
    val apiKeyId: String? = null,
    val modelLatencyMs: Long? = null,

    // ---- 辅助信息 ----
    val tags: List<String> = emptyList(),
    val source: String? = null,
    val notes: String? = null,

    // ---- 错误信息 ----
    val errorMessage: String? = null,

    // ---- 时间戳 ----
    val analyzedAt: String? = null,
) {
    enum class Status { PENDING, PROCESSING, COMPLETED, FAILED }
}

package com.ifmix.api.core.service.antique

/**
 * 古物扫描结果（AI 分析产出）。
 *
 * 由 SpringAiScanRunner 将模型 JSON 响应映射为完整结果。
 * 所有字段采用 snake_case 序列化，与 AI 模型输出约定一致。
 */
data class ScanResult(
    // ---- 基础标识 ----
    /** 扫描任务唯一 ID。 */
    val scanId: String,

    /** 扫描状态：PENDING / PROCESSING / COMPLETED / FAILED。 */
    val status: Status = Status.PENDING,

    // ---- 核心识别 ----
    /** 是否判定为古物（true = 古物，false = 非古物/现代物品）。 */
    val isAntique: Boolean? = null,

    /** 古物名称（中文，如 "青花瓷瓶"）。 */
    val name: String? = null,

    /** 古物英文名称。 */
    val nameEn: String? = null,

    /** 古物别名/俗称列表。 */
    val aliases: List<String> = emptyList(),

    /** 面向用户的介绍文案。 */
    val description: String? = null,

    /** 一级分类标签。 */
    val primaryCategory: String? = null,

    /** 二级分类标签。 */
    val secondaryCategory: String? = null,

    /** 三级分类标签。 */
    val tertiaryCategory: String? = null,

    // ---- 年代判定 ----
    /** 年代描述（如 "明代"、"清代乾隆"）。 */
    val dynasty: String? = null,

    /** 年代区间（起始年份，如 1368）。 */
    val yearFrom: Int? = null,

    /** 年代区间（结束年份，如 1644）。 */
    val yearTo: Int? = null,

    /** 年代置信度分数（0-1）。 */
    val dynastyConfidence: Double? = null,

    // ---- 材质工艺 ----
    /** 主要材质（如 "高岭土"、"青铜"）。 */
    val material: String? = null,

    /** 次要材质列表。 */
    val materials: List<String> = emptyList(),

    /** 工艺特征（如 "釉下彩绘"、"失蜡法"）。 */
    val technique: String? = null,

    /** 工艺特征列表。 */
    val techniques: List<String> = emptyList(),

    // ---- 外观特征 ----
    /** 颜色描述（如 "青花白地"）。 */
    val colors: List<String> = emptyList(),

    /** 形状描述（如 "瓶形"、"盘形"）。 */
    val shape: String? = null,

    /** 表面纹理（如 "光滑"、"磨砂"）。 */
    val texture: String? = null,

    /** 装饰纹样列表（如 "云龙纹"、"缠枝莲"）。 */
    val decorations: List<String> = emptyList(),

    /** 是否有铭文/款识。 */
    val hasInscription: Boolean? = null,

    /** 铭文内容。 */
    val inscription: String? = null,

    // ---- 尺寸重量 ----
    /** 高度（单位：cm）。 */
    val heightCm: Double? = null,

    /** 宽度（单位：cm）。 */
    val widthCm: Double? = null,

    /** 深度/直径（单位：cm）。 */
    val depthCm: Double? = null,

    /** 重量（单位：g）。 */
    val weightG: Double? = null,

    // ---- 价值评估 ----
    /** 服务端格式化的价格范围字符串（如 "¥5,000-10,000"，含货币符号）。前端可直接展示。 */
    val priceRange: String? = null,

    /** 估价数值（单位由 priceCurrency 决定），用于前端自定义格式化。 */
    val priceMin: Double? = null,

    /** 估价数值（单位由 priceCurrency 决定），用于前端自定义格式化。 */
    val priceMax: Double? = null,

    /** 估价币种代码（如 CNY、USD）。 */
    val priceCurrency: String? = null,

    /** 价值置信度（0-1）。 */
    val valueConfidence: Double? = null,

    // ---- 真伪鉴定 ----
    /** 真伪判定: authentic / suspicious / fake / uncertain */
    val authenticity: String? = null,

    /** 真伪置信度（0-1）。 */
    val authenticityConfidence: Double? = null,

    /** 疑点说明（如有）。 */
    val authenticityNotes: String? = null,

    // ---- 保存状况 ----
    /** 保存状况: pristine / excellent / good / fair / poor / damaged */
    val condition: String? = null,

    /** 瑕疵描述列表。 */
    val flaws: List<String> = emptyList(),

    /** 修复历史描述。 */
    val restorationHistory: String? = null,

    // ---- 综合评分 ----
    /** 综合评分（0-100） */
    val score: Int? = null,

    /** 综合置信度（0.0-1.0） */
    val confidence: Double? = null,

    // ---- 辅助信息 ----
    /** 标签列表（通用分类标签）。 */
    val tags: List<String> = emptyList(),

    /** 额外备注/自由文本。 */
    val notes: String? = null,

    // ---- 错误信息 ----
    /** 扫描失败时的错误信息。 */
    val errorMessage: String? = null,

    // ---- 时间戳 ----
    /** AI 分析完成时间（epoch millis）。 */
    val analyzedAt: Long? = null,
) {
    /** 扫描状态枚举。 */
    enum class Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
    }
}

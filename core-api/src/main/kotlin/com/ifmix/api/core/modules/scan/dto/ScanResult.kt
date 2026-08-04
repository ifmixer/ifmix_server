package com.ifmix.api.core.modules.scan.dto

import com.ifmix.api.core.entity.enums.ScanStatus
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 古物扫描结果（AI 分析产出）。
 *
 * **所有文本字段都是 AI 模型自由输出**（非有限枚举），语言跟随请求头 `x-lang`。
 * 前端直接原样展示即可，无需 i18n 翻译。
 *
 * 字段优先级建议：
 * - **首屏必显**：name, isAntique, description, priceRange, score, dynasty, primaryCategory, authenticity, condition
 * - **详情区**：material(s), technique(s), shape, colors, decorations, inscription, heightCm/widthCm/depthCm/weightG
 * - **折叠区**：confidence, yearFrom/yearTo, priceMin/priceMax/priceCurrency, authenticityNotes, flaws, restorationHistory, notes, tags, aliases, nameEn
 */
@Schema(description = "AI 古物识别结果。文本字段（name/dynasty/description/primaryCategory 等）跟随 x-lang 返回本地化文本；authenticity/condition 为固定英文枚举 token，不本地化。")
data class ScanResult(
    // ---- 基础标识 ----
    @Schema(description = "历史兼容字段，前端不需要使用。", deprecated = true)
    val scanId: String,

    @Schema(description = "扫描状态。customer 接口中通常为 COMPLETED。PENDING 仅出现在内部创建但未触发 AI 的记录。")
    val status: ScanStatus = ScanStatus.PENDING,

    // ---- 首屏必显 ----
    @Schema(description = "是否判定为古物（true=古物，false=非古物/现代物品）")
    val isAntique: Boolean? = null,

    @Schema(description = "古物名称（主标题，跟随 x-lang 语言）")
    val name: String? = null,

    @Schema(description = "古物英文名称（非中文 locale 时用作标题）")
    val nameEn: String? = null,

    @Schema(description = "别名/俗称列表")
    val aliases: List<String> = emptyList(),

    @Schema(description = "面向用户的介绍文案（主要描述文本，首屏展示）")
    val description: String? = null,

    @Schema(description = "一级分类（AI 自由输出，如「陶瓷」「青铜器」「书画」）")
    val primaryCategory: String? = null,

    @Schema(description = "二级分类（如「青花」「粉彩」）")
    val secondaryCategory: String? = null,

    @Schema(description = "三级分类")
    val tertiaryCategory: String? = null,

    // ---- 年代判定 ----
    @Schema(description = "年代描述（AI 自由输出，如「明代」「清代乾隆」）")
    val dynasty: String? = null,

    @Schema(description = "年代区间起始年份（如 1368）")
    val yearFrom: Int? = null,

    @Schema(description = "年代区间结束年份（如 1644）")
    val yearTo: Int? = null,

    @Schema(description = "年代判定置信度", minimum = "0", maximum = "1")
    val dynastyConfidence: Double? = null,

    // ---- 材质工艺 ----
    @Schema(description = "主要材质（materials 数组的第一项快捷访问）")
    val material: String? = null,

    @Schema(description = "材质列表（完整）")
    val materials: List<String> = emptyList(),

    @Schema(description = "主要工艺（techniques 数组的第一项快捷访问）")
    val technique: String? = null,

    @Schema(description = "工艺特征列表（完整）")
    val techniques: List<String> = emptyList(),

    // ---- 外观特征 ----
    @Schema(description = "颜色描述列表")
    val colors: List<String> = emptyList(),

    @Schema(description = "形状描述（AI 自由输出，如「瓶形」「盘形」）")
    val shape: String? = null,

    @Schema(description = "表面纹理（AI 自由输出，如「光滑」「磨砂」）")
    val texture: String? = null,

    @Schema(description = "装饰纹样列表")
    val decorations: List<String> = emptyList(),

    @Schema(description = "是否有铭文/款识")
    val hasInscription: Boolean? = null,

    @Schema(description = "铭文内容")
    val inscription: String? = null,

    // ---- 尺寸重量 ----
    @Schema(description = "高度（cm）")
    val heightCm: Double? = null,

    @Schema(description = "宽度（cm）")
    val widthCm: Double? = null,

    @Schema(description = "深度/直径（cm）")
    val depthCm: Double? = null,

    @Schema(description = "重量（g）")
    val weightG: Double? = null,

    // ---- 价值评估 ----
    @Schema(description = "服务端格式化的价格范围（含货币符号，如「¥5,000-10,000」）。前端可直接展示，无需配合 priceCurrency 处理。")
    val priceRange: String? = null,

    @Schema(description = "估价最低值（数值，单位由 priceCurrency 决定）。用于前端自定义格式化场景。")
    val priceMin: Double? = null,

    @Schema(description = "估价最高值（数值，单位由 priceCurrency 决定）")
    val priceMax: Double? = null,

    @Schema(description = "估价币种代码（如 CNY、USD）")
    val priceCurrency: String? = null,

    @Schema(description = "价值评估置信度", minimum = "0", maximum = "1")
    val valueConfidence: Double? = null,

    // ---- 真伪鉴定 ----
    @Schema(description = "真伪判定（固定英文 token，不跟随 x-lang）。前端用于徽章分类。服务端保证返回值在枚举范围内，模型集合外输出统一映射为 UNCERTAIN。")
    val authenticity: Authenticity? = null,

    @Schema(description = "真伪判定置信度", minimum = "0", maximum = "1")
    val authenticityConfidence: Double? = null,

    @Schema(description = "真伪疑点说明")
    val authenticityNotes: String? = null,

    // ---- 保存状况 ----
    @Schema(description = "保存状况（固定英文 token，不跟随 x-lang）。服务端保证返回值在枚举范围内，模型集合外输出统一映射为 FAIR。前端可用穷举分支。")
    val condition: Condition? = null,

    @Schema(description = "瑕疵描述列表")
    val flaws: List<String> = emptyList(),

    @Schema(description = "修复历史描述")
    val restorationHistory: String? = null,

    // ---- 综合评分 ----
    @Schema(description = "综合评分", minimum = "0", maximum = "100")
    val score: Int? = null,

    @Schema(description = "综合置信度", minimum = "0", maximum = "1")
    val confidence: Double? = null,

    // ---- 辅助信息 ----
    @Schema(description = "通用分类标签列表")
    val tags: List<String> = emptyList(),

    @Schema(description = "AI 补充备注（内部参考，非面向用户的主文案。用户主文案用 description 字段）")
    val notes: String? = null,

    // ---- 错误信息 ----
    @Schema(description = "扫描失败时的错误信息（status=FAILED 时有值）")
    val errorMessage: String? = null,

    // ---- 时间戳 ----
    @Schema(description = "AI 分析完成时间（epoch millis）")
    val analyzedAt: Long? = null,
) {
    /** 真伪判定枚举（固定英文 token）。 */
    enum class Authenticity {
        AUTHENTIC,
        SUSPICIOUS,
        FAKE,
        UNCERTAIN,
    }

    /** 保存状况枚举（固定英文 token）。 */
    enum class Condition {
        PRISTINE,
        EXCELLENT,
        GOOD,
        FAIR,
        POOR,
        DAMAGED,
    }
}

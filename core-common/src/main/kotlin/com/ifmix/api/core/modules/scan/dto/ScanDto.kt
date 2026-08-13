package com.ifmix.api.core.modules.scan.dto

import com.ifmix.api.core.entity.enums.ScanStatus
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

/**
 * 古物扫描结果（AI 分析产出）。
 *
 * 所有文本字段都是 AI 模型自由输出，语言跟随请求头 `x-lang`。
 */
@Schema(description = "AI 古物识别结果。文本字段跟随 x-lang 返回本地化文本；authenticity/condition 为固定英文枚举 token。")
data class ScanResult(
    @Schema(description = "历史兼容字段，前端不需要使用。", deprecated = true)
    val scanId: String,

    @Schema(description = "扫描状态。customer 接口中通常为 COMPLETED。")
    val status: ScanStatus = ScanStatus.PENDING,

    // ---- 首屏必显 ----
    @Schema(description = "是否判定为古物（true=古物，false=非古物/现代物品）")
    val isAntique: Boolean? = null,

    @Schema(description = "古物名称（主标题，跟随 x-lang 语言）")
    val name: String? = null,

    @Schema(description = "别名/俗称列表")
    val aliases: List<String> = emptyList(),

    @Schema(description = "面向用户的介绍文案（主要描述文本，首屏展示）")
    val description: String? = null,

    @Schema(description = "一级分类（AI 自由输出，如「陶瓷」「青铜器」）")
    val primaryCategory: String? = null,

    @Schema(description = "二级分类（如「青花」「粉彩」）")
    val secondaryCategory: String? = null,

    @Schema(description = "三级分类")
    val tertiaryCategory: String? = null,

    // ---- 年代判定 ----
    @Schema(description = "年代描述（AI 自由输出，如「明代」「清代乾隆」）")
    val dynasty: String? = null,

    @Schema(description = "年代起始年份")
    val yearFrom: Int? = null,

    @Schema(description = "年代结束年份")
    val yearTo: Int? = null,

    @Schema(description = "年代判定置信度 0-1")
    val dynastyConfidence: Double? = null,

    // ---- 材质与工艺 ----
    @Schema(description = "材质（如「高岭土」「青铜」）")
    val material: String? = null,

    @Schema(description = "材料列表")
    val materials: List<String> = emptyList(),

    @Schema(description = "工艺技术（如「釉下彩绘」）")
    val technique: String? = null,

    @Schema(description = "工艺技术列表")
    val techniques: List<String> = emptyList(),

    @Schema(description = "形状描述")
    val shape: String? = null,

    // ---- 尺寸 ----
    @Schema(description = "高度（厘米）")
    val heightCm: Double? = null,

    @Schema(description = "宽度（厘米）")
    val widthCm: Double? = null,

    @Schema(description = "深度（厘米）")
    val depthCm: Double? = null,

    @Schema(description = "重量（克）")
    val weightG: Double? = null,

    // ---- 价值评估 ----
    @Schema(description = "价格区间描述（如「5000-10000元」）")
    val priceRange: String? = null,

    @Schema(description = "价格下限")
    val priceMin: Double? = null,

    @Schema(description = "价格上限")
    val priceMax: Double? = null,

    @Schema(description = "价格货币代码（如 CNY、USD）")
    val priceCurrency: String? = null,

    @Schema(description = "价值评估置信度 0-1")
    val valueConfidence: Double? = null,

    // ---- 真伪判定 ----
    @Schema(description = "真伪判定结果")
    val authenticity: Authenticity? = null,

    @Schema(description = "真伪判定置信度 0-1")
    val authenticityConfidence: Double? = null,

    @Schema(description = "真伪判定备注")
    val authenticityNotes: String? = null,

    // ---- 品相 ----
    @Schema(description = "品相等级")
    val condition: Condition? = null,

    @Schema(description = "综合评分 0-100")
    val score: Int? = null,

    @Schema(description = "整体置信度 0-1")
    val confidence: Double? = null,

    // ---- 特征标记 ----
    @Schema(description = "标签列表")
    val tags: List<String> = emptyList(),

    @Schema(description = "颜色列表")
    val colors: List<String> = emptyList(),

    @Schema(description = "装饰纹样列表")
    val decorations: List<String> = emptyList(),

    @Schema(description = "瑕疵列表")
    val flaws: List<String> = emptyList(),

    @Schema(description = "是否有铭文")
    val hasInscription: Boolean? = null,

    @Schema(description = "铭文内容")
    val inscription: String? = null,

    @Schema(description = "质感描述")
    val texture: String? = null,

    // ---- 其他 ----
    @Schema(description = "修复历史")
    val restorationHistory: String? = null,

    @Schema(description = "备注")
    val notes: String? = null,

    @Schema(description = "错误信息（失败时使用）")
    val errorMessage: String? = null,

    @Schema(description = "分析时间戳（毫秒）")
    val analyzedAt: Long? = null,
) {
    enum class Authenticity { AUTHENTIC, FAKE, UNCERTAIN, PARTIAL }
    enum class Condition { EXCELLENT, GOOD, FAIR, POOR, DAMAGED }
}

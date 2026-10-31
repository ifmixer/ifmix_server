package com.ifmix.core.api.modules.ai.service

import com.ifmix.core.api.dto.ai.ScanInput
import com.ifmix.core.api.dto.ai.ScanType
import com.ifmix.core.api.entity.ai.ImageCategories

/**
 * 扫描系统提示词加载器（**按项目实例化**，由 [ScanPromptRegistry] 缓存持有）。
 *
 * 提示词文件按 `prompts/${name}_${promptVersion}.md` 命名加载，一个项目有两组：
 *  - basic 组（`${basicName}`）：只返回 `basic_result`；
 *  - full 组（`${fullName}`）：返回 `basic_result` + `premium_result`。
 *
 * full 组的命名两个 app 不一致（antique 叫 `-deep-research`，它的 premium 由 DeepResearch
 * 异步任务产出；tcgcard 叫 `-premium`，v1 单次扫描就同时产出两者），故由配置逐项目指定
 * （设计文档 §8.1/§8.2）。差异走配置、不写 projectId 分支 —— 见 §4 反目标。
 *
 * promptVersion 随扫描结果落库，用于追溯结果由哪个版本提示词生成。
 */
class ScanPrompt(
    val promptVersion: String,
    private val basicName: String = DEFAULT_BASIC_NAME,
    private val fullName: String = DEFAULT_FULL_NAME,
    /**
     * 普通扫描（[ScanType.BASIC]）是否直接走 full 组。
     * tcgcard v1 = true（一次调用同时产出 basic + premium）；antique = false。
     */
    val basicScanUsesFullTemplate: Boolean = false,
) {

    private val template: String = loadPrompt(basicName)
    private val fullTemplate: String = loadPrompt(fullName)

    private fun loadPrompt(promptName: String): String {
        val path = "prompts/${promptName}_${promptVersion}.md"
        return ScanPrompt::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Classpath resource not found: $path")
    }

    private fun fill(tpl: String, input: ScanInput): String {
        val resolvedLang = input.locale?.takeIf { it.isNotBlank() } ?: "en-US"
        val resolvedCurrency = input.currency?.takeIf { it.isNotBlank() } ?: "USD"
        val resolvedRegion = input.country?.takeIf { it.isNotBlank() } ?: "Not specified"

        return tpl
            .replace("{{CURRENT_DATE}}", input.date.toString())
            .replace("{{RESPONSE_LOCALE}}", resolvedLang)
            .replace("{{MARKET_REGION}}", resolvedRegion)
            .replace("{{VALUATION_CURRENCY}}", resolvedCurrency)
    }

    /** Basic 系统提示词 —— 只产出 basic_result。 */
    fun systemPrompt(input: ScanInput): String = fill(template, input)

    /** Full 系统提示词 —— 产出 basic_result + premium_result。 */
    fun fullSystemPrompt(input: ScanInput): String = fill(fullTemplate, input)

    /** 旧名：DeepResearch 异步任务用的就是 full 组。 */
    fun deepResearchSystemPrompt(input: ScanInput): String = fullSystemPrompt(input)

    /** 按 ScanType 选模板：BASIC 是否走 full 组由项目配置决定。 */
    fun systemPromptFor(input: ScanInput): String = when (input.type) {
        ScanType.DEEP_RESEARCH -> fullSystemPrompt(input)
        ScanType.BASIC -> if (basicScanUsesFullTemplate) fullSystemPrompt(input) else systemPrompt(input)
    }

    /**
     * Minimal user prompt — task instruction only. Log this for debugging.
     *
     * **每张图是哪个视角必须明说**（设计 §8.3）：卡牌背面承载版本 / 版权 / 标志，
     * 靠模型自己猜哪张是背面会直接影响 variant / edition / 真伪判断。
     * 只要所有图都带了 [VIEW_LABELS] 里的分类码，就补一行
     * `Image 1 = front, Image 2 = back`（顺序与 items 一致）。
     *
     * **不看 projectId**：分类码是数据不是项目分支（§4 反目标）。antique 的图片恒为
     * MAIN(0)，不在码表里 → 走原来的通用句，行为逐字不变。
     */
    fun userPrompt(input: ScanInput): String {
        val imageCount = input.items.size
        require(imageCount > 0) { "imageCount must be > 0" }

        val viewLine = buildViewLine(input)
        val task = if (imageCount == 1) {
            listOfNotNull("Analyze the attached image.", viewLine).joinToString(" ")
        } else {
            listOfNotNull(
                "Analyze the $imageCount attached images as views of the same primary object unless the visual evidence clearly indicates otherwise.",
                viewLine,
            ).joinToString(" ")
        }
        // 语言锚:系统提示词声明了 RESPONSE_LOCALE,但离生成最近的是用户消息 —— 实测模型仍会从
        // 被摄物语言或估值货币漂移(zh-TW + KRW 扫韩钞 → 韩语输出)。locale 缺省与 fill() 同口径。
        val locale = input.locale?.takeIf { it.isNotBlank() } ?: "en-US"
        return "$task Respond in $locale; all user-facing text must use that language."
    }

    /**
     * 生成 `Image 1 = front, Image 2 = back.` 这样的视角说明行。
     *
     * - 全部是 MAIN（antique 的常态、toyscan 的首扫单图）→ 没有视角信息要传达，返回 null，
     *   保持改造前的通用措辞；
     * - MAIN 与其他分类**混合**（toyscan 补充扫描 = 原图 MAIN + 补充图）→ MAIN 标为
     *   `primary photo`，其余按码表（设计 §8.2）；
     * - 任一图的分类缺失或不在码表内 → 返回 null（保持改造前的措辞，不误导模型）。
     */
    private fun buildViewLine(input: ScanInput): String? {
        if (input.items.all { it.category == ImageCategories.MAIN }) return null
        val labels = input.items.map { item ->
            when (item.category) {
                ImageCategories.MAIN -> "primary photo"
                else -> item.category?.let { code -> VIEW_LABELS[code] }
            }
        }
        if (labels.any { it == null }) return null
        val body = labels.mapIndexed { index, label -> "Image ${index + 1} = $label" }.joinToString(", ")
        return "$body."
    }

    private companion object {
        /** 配置整段缺失时的回落（= 改造前的硬编码行为）。 */
        const val DEFAULT_BASIC_NAME = "antique-scan-system-basic"
        const val DEFAULT_FULL_NAME = "antique-scan-system-deep-research"

        /**
         * 图片分类码 → user prompt 里的视角名。
         * MAIN(0) 不在码表里：它没有视角语义，全部为 MAIN 时整体退回通用措辞；
         * 与其他分类混合时在 [buildViewLine] 里单独标为 `primary photo`。
         * OTHER(1000) 无视角语义，出现在集合里就退回通用措辞。
         */
        val VIEW_LABELS: Map<Int, String> = linkedMapOf(
            10 to "front",
            20 to "back",
            30 to "bottom",
            40 to "maker's mark",
            50 to "damage / wear",
            60 to "dimensions (with a reference object)",
            70 to "price tag",
            80 to "documents / provenance",
            90 to "detail",
        )
    }
}

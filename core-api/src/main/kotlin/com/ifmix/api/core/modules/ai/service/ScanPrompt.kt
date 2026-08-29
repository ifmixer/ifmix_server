package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.dto.ai.ScanInput
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 古物扫描系统提示词加载器。
 *
 * 提示词文件按 `prompts/${promptName}_${promptVersion}.md` 命名加载，
 * promptVersion 由 `app.agnes.ai.promptVersion`（默认 v10）配置，可用 SCAN_PROMPT_VERSION 覆盖。
 * promptVersion 会随扫描结果落库，用于追溯结果由哪个版本提示词生成。
 */
@Component
class ScanPrompt(
    @Value("\${app.agnes.ai.promptVersion:v10}") val promptVersion: String,
) {

    private val template: String = loadPrompt("antique-scan-system-basic")
    private val deepResearchTemplate: String = loadPrompt("antique-scan-system-deep-research")

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

    /** Build the complete system prompt with runtime values filled in. */
    fun systemPrompt(input: ScanInput): String = fill(template, input)

    /** Deep-research system prompt — returns both basic_result and premium_result. */
    fun deepResearchSystemPrompt(input: ScanInput): String = fill(deepResearchTemplate, input)

    /** Minimal user prompt — task instruction only. Log this for debugging. */
    fun userPrompt(input: ScanInput): String {
        val imageCount = input.items.size
        require(imageCount > 0) { "imageCount must be > 0" }

        return if (imageCount == 1) {
            "Analyze the attached image."
        } else {
            "Analyze the $imageCount attached images as views of the same primary object unless the visual evidence clearly indicates otherwise."
        }
    }
}

package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.dto.ai.ScanInput

/**
 * V5 Production Prompt for AI antique scanning.
 *
 * Single system prompt loaded from `prompts/scan-system.md`.
 * Contains {{placeholders}} replaced per-request with runtime values.
 */
object ScanPrompt {

//    private val template: String = loadResource("prompts/scan-system_20260823.md")

    private val template: String = loadResource("prompts/scan-system-basic.md")

    private val deepResearchTemplate: String = loadResource("prompts/scan-system-deep-research.md")

    private fun loadResource(path: String): String =
        ScanPrompt::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Classpath resource not found: $path")

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

    /**
     * Build the complete system prompt with runtime values filled in.
     */
    fun systemPrompt(input: ScanInput): String = fill(template, input)

    /**
     * Deep-research system prompt — returns both basic_result and premium_result.
     */
    fun deepResearchSystemPrompt(input: ScanInput): String = fill(deepResearchTemplate, input)

    /**
     * Minimal user prompt — task instruction only. Log this for debugging.
     */
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

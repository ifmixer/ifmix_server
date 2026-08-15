package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.modules.scan.dto.ScanInput

/**
 * V5 Production Prompt for AI antique scanning.
 *
 * Single system prompt loaded from `prompts/scan-system.md`.
 * Contains {{placeholders}} replaced per-request with runtime values.
 */
object ScanPrompt {

    private val template: String = loadResource("prompts/scan-system-simple.md")

    private fun loadResource(path: String): String =
        ScanPrompt::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Classpath resource not found: $path")

    /**
     * Build the complete system prompt with runtime values filled in.
     */
    fun systemPrompt(input: ScanInput): String {
        val resolvedLang = input.lang?.takeIf { it.isNotBlank() } ?: "en-US"
        val resolvedCurrency = input.currency?.takeIf { it.isNotBlank() } ?: "USD"
        val resolvedRegion = input.country?.takeIf { it.isNotBlank() } ?: "Not specified"

        return template
            .replace("{{CURRENT_DATE}}", input.date.toString())
            .replace("{{RESPONSE_LANGUAGE}}", resolvedLang)
            .replace("{{MARKET_REGION}}", resolvedRegion)
            .replace("{{VALUATION_CURRENCY}}", resolvedCurrency)
    }

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

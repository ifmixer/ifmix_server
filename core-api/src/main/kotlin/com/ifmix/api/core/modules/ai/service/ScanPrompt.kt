package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.modules.scan.dto.ScanInput

/**
 * V5 Production Prompt for AI antique scanning.
 *
 * Prompts are loaded from classpath markdown files:
 * - `prompts/scan-part1-context.md` — runtime context & priority weights (contains {{placeholders}})
 * - `prompts/scan-part2-behavior.md` — static scanner behavior, rules, output schema
 *
 * Architecture:
 * - [systemPrompt]: PART1(filled) + PART2 → complete system message.
 * - [userPrompt]: Minimal task instruction. Log this for debugging.
 */
object ScanPrompt {

    private val part1Template: String = loadResource("prompts/scan-part1-context.md")
    private val part2Body: String = loadResource("prompts/scan-part2-behavior.md")

    private fun loadResource(path: String): String =
        ScanPrompt::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Classpath resource not found: $path")

    /**
     * Build the complete system prompt: PART1 (runtime context, filled) + PART2 (static behavior).
     *
     * PART1 is placed first so language/currency/region have maximum model attention weight.
     *
     * @param input scan input (uses lang, country, currency, date)
     */
    fun systemPrompt(input: ScanInput): String {
        val resolvedLang = input.lang?.takeIf { it.isNotBlank() } ?: "en-US"
        val resolvedCurrency = input.currency?.takeIf { it.isNotBlank() } ?: "USD"
        val resolvedRegion = input.country?.takeIf { it.isNotBlank() } ?: "Not specified"

        val part1 = part1Template
            .replace("{{CURRENT_DATE}}", input.date.toString())
            .replace("{{RESPONSE_LANGUAGE}}", resolvedLang)
            .replace("{{MARKET_REGION}}", resolvedRegion)
            .replace("{{VALUATION_CURRENCY}}", resolvedCurrency)

        return "$part1\n\n$part2Body"
    }

    /**
     * Build the user prompt — minimal task instruction.
     * This is short and per-request varying. Log this for debugging.
     *
     * @param input scan input containing images
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

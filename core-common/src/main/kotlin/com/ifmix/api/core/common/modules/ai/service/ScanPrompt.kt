package com.ifmix.api.core.common.modules.ai.service

/**
 * Prompt template for AI antique scanning.
 *
 * System prompt instructs the model to return JSON matching ScanResult schema (snake_case).
 * Locale-aware: text fields are output in the user's language, prices in user's currency.
 */
object ScanPrompt {

    /**
     * System text — permanent instructions for all requests.
     *
     * Model instructions:
     * - Always respond in valid JSON format
     * - Use snake_case for all keys
     * - Fill all detectable fields, null for unknown
     * - No explanation outside JSON
     * - Locale-aware output for text and price fields
     */
    val SYSTEM_TEXT = """
        You are a professional antique appraiser and visual analysis engine.
        Your task is to analyze images of objects and return structured results in JSON format.

        RULES:
        1. ALWAYS return ONLY valid JSON. No markdown, no explanation, no extra text.
        2. Use snake_case for ALL JSON keys.
        3. Include every field you can confidently determine. Leave unknown fields as null.
        4. Do NOT guess — if unsure about a field, set it to null.
        5. For boolean fields, use true/false (not strings).
        6. For numeric fields, use numbers (not strings).
        7. For list fields, use empty arrays [] when no items match.
        8. The following fields are FIXED ENGLISH TOKENS and must NOT be localized:
           - status: "COMPLETED" | "FAILED"
           - authenticity: "AUTHENTIC" | "SUSPICIOUS" | "FAKE" | "UNCERTAIN"
           - condition: "PRISTINE" | "EXCELLENT" | "GOOD" | "FAIR" | "POOR" | "DAMAGED"

        OUTPUT SCHEMA:
        - scan_id: string (UUID or identifier)
        - status: "COMPLETED" | "FAILED"
        - is_antique: boolean
        - name: string or null
        - name_en: string or null (English name, always present regardless of locale)
        - aliases: array of strings (alternative names)
        - description: string or null (user-facing introduction text)
        - primary_category: string or null
        - secondary_category: string or null
        - tertiary_category: string or null
        - dynasty: string or null
        - year_from: number or null
        - year_to: number or null
        - dynasty_confidence: number or null (0-1)
        - material: string or null
        - materials: array of strings
        - technique: string or null
        - techniques: array of strings
        - shape: string or null
        - texture: string or null
        - colors: array of strings
        - decorations: array of strings
        - has_inscription: boolean or null
        - inscription: string or null
        - height_cm: number or null
        - width_cm: number or null
        - depth_cm: number or null
        - weight_g: number or null
        - condition: "PRISTINE" | "EXCELLENT" | "GOOD" | "FAIR" | "POOR" | "DAMAGED" or null
        - flaws: array of strings
        - restoration_history: string or null
        - authenticity: "AUTHENTIC" | "SUSPICIOUS" | "FAKE" | "UNCERTAIN" or null
        - authenticity_confidence: number or null (0-1)
        - authenticity_notes: string or null
        - price_range: string or null (formatted with currency symbol, e.g. "¥5,000-10,000")
        - price_min: number or null
        - price_max: number or null
        - price_currency: string or null (ISO 4217 code)
        - value_confidence: number or null (0-1)
        - score: number or null (0-100)
        - confidence: number or null (0-1)
        - tags: array of strings
        - notes: string or null
        - error_message: string or null
    """.trimIndent()

    /**
     * Build locale instruction block based on user's locale settings.
     *
     * @param lang language code (e.g. "zh-Hans", "en", "ja")
     * @param country country code (e.g. "CN", "US", "JP")
     * @param currency currency code (e.g. "CNY", "USD", "JPY")
     * @return locale instruction text to prepend to user prompt
     */
    fun localeInstruction(lang: String?, country: String?, currency: String?): String {
        if (lang == null && country == null && currency == null) return ""

        val parts = mutableListOf<String>()

        parts += "LOCALIZATION:"

        if (lang != null) {
            parts += "- Output ALL text fields (name, description, dynasty, primary_category, secondary_category, tertiary_category, material, materials, technique, techniques, shape, texture, colors, decorations, inscription, flaws, restoration_history, authenticity_notes, aliases, tags, notes) in language: $lang"
        }

        if (country != null) {
            parts += "- The user is located in: $country. Consider local market context for pricing and cultural relevance."
        }

        if (currency != null) {
            parts += "- Use currency $currency for all price fields. Set price_currency to \"$currency\". Format price_range with the appropriate currency symbol (e.g. ¥ for CNY/JPY, \$ for USD, € for EUR, £ for GBP)."
        } else {
            parts += "- Default to USD for price fields if no currency specified."
        }

        return parts.joinToString("\n")
    }

    /**
     * User text template — includes locale instructions and image count reference.
     * Images are sent as media attachments, not URLs in the text.
     *
     * @param imageCount number of images attached
     * @param lang user language preference
     * @param country user country
     * @param currency user currency preference
     * @return complete user prompt
     */
    fun userPrompt(imageCount: Int, lang: String? = null, country: String? = null, currency: String? = null): String {
        val locale = localeInstruction(lang, country, currency)
        val localeBlock = if (locale.isNotEmpty()) "$locale\n\n" else ""
        val imageRef = if (imageCount == 1) {
            "Analyze the attached image for antique identification."
        } else {
            "Analyze the $imageCount attached images for antique identification. They show the same object from different angles."
        }
        return "${localeBlock}${imageRef} Return the result as JSON."
    }
}

package com.ifmix.api.core.common.ai

/**
 * Scan prompt template for AI antique scanning.
 *
 * The system prompt instructs the model to always return JSON matching the ScanResult schema
 * (snake_case). Supports locale-aware prompt prefixes.
 */
object ScanPrompt {

    /**
     * System text — permanent instructions for all requests.
     *
     * The model is instructed to:
     * - Always respond with valid JSON only
     * - Use snake_case for all keys
     * - Fill every detectable field; leave unknown fields as null
     * - Never include explanation outside the JSON
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
        8. The following fields are fixed English tokens and must NOT be localized:
           - status: "COMPLETED" | "FAILED"
           - authenticity: "AUTHENTIC" | "SUSPICIOUS" | "FAKE" | "UNCERTAIN"
           - condition: "PRISTINE" | "EXCELLENT" | "GOOD" | "FAIR" | "POOR" | "DAMAGED"

        OUTPUT SCHEMA:
        - scan_id: string (UUID or identifier)
        - status: "COMPLETED" | "FAILED"
        - is_antique: boolean
        - name: string or null (localized name)
        - name_en: string or null (English name, always output if determinable)
        - aliases: array of strings or null (alternative names / colloquial terms)
        - description: string or null (user-facing description in the target locale)
        - primary_category: string or null (top-level category, e.g. "Ceramics")
        - secondary_category: string or null (mid-level category, e.g. "Porcelain")
        - tertiary_category: string or null (specific sub-type, e.g. "Blue-and-white")
        - dynasty: string or null
        - year_from: number or null
        - year_to: number or null
        - dynasty_confidence: number 0-1 or null
        - materials: array of strings or null (plural; e.g. ["kaolinite", "cobalt"])
        - materials_primary: string or null (legacy single material)
        - techniques: array of strings or null (plural; e.g. ["underglaze painting"])
        - techniques_primary: string or null (legacy single technique)
        - texture: string or null
        - shape: string or null
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
        - authenticity: "AUTHENTIC" | "SUSPICIOUS" | "FAKE" | "UNCERTAIN" or null
        - authenticity_confidence: number 0-1 or null
        - authenticity_notes: string or null
        - restoration_history: string or null
        - price_range: string or null
        - price_min: number or null
        - price_max: number or null
        - price_currency: string or null (ISO 4217 code, e.g. "CNY", "USD")
        - value_confidence: number 0-1 or null
        - score: number or null
        - confidence: number or null
        - tags: array of strings
        - notes: string or null
        - error_message: string or null
    """.trimIndent()

    /**
     * Generate a locale-aware prompt prefix.
     *
     * For known locales, returns a short instruction telling the model to output
     * description / name / aliases in the target language. For unknown locales,
     * falls back to English.
     *
     * @param lang  ISO 639-1 language code (e.g. "en", "id")
     * @param country  ISO 3166-1 alpha-2 country code (e.g. "US", "ID"), nullable
     * @param currency  ISO 4217 currency code (e.g. "USD", "IDR"), nullable
     * @return prompt prefix string to prepend before SYSTEM_TEXT
     */
    fun localeInstruction(lang: String?, country: String?, currency: String?): String {
        val locale = "$lang-${country ?: ""}"
        return when {
            lang == "id" || lang == "in" -> """
                [Locale: Indonesian (id)]
                - Output 'name', 'aliases', and 'description' in Indonesian (Bahasa Indonesia).
                - Use 'IDR' for price_currency when country is Indonesia.
                - Keep all fixed tokens (status, authenticity, condition) in English.
            """.trimIndent()
            lang == "en" || lang.isNullOrBlank() -> """
                [Locale: English (default)]
                - Output 'name', 'aliases', and 'description' in English.
                - Use 'USD' for price_currency when no currency is specified.
                - Keep all fixed tokens (status, authenticity, condition) in English.
            """.trimIndent()
            lang == "zh" || lang == "zh-CN" -> """
                [Locale: Chinese (zh-CN)]
                - Output 'name', 'aliases', and 'description' in Simplified Chinese.
                - Use 'CNY' for price_currency when country is China.
                - Keep all fixed tokens (status, authenticity, condition) in English.
            """.trimIndent()
            lang == "ja" -> """
                [Locale: Japanese (ja)]
                - Output 'name', 'aliases', and 'description' in Japanese.
                - Use 'JPY' for price_currency when country is Japan.
                - Keep all fixed tokens (status, authenticity, condition) in English.
            """.trimIndent()
            lang == "ar" -> """
                [Locale: Arabic (ar)]
                - Output 'name', 'aliases', and 'description' in Arabic.
                - Keep all fixed tokens (status, authenticity, condition) in English.
            """.trimIndent()
            else -> """
                [Locale: ${lang ?: "unknown"}]
                - Output 'name', 'aliases', and 'description' in the requested locale when possible.
                - Keep all fixed tokens (status, authenticity, condition) in English.
            """.trimIndent()
        }
    }

    /**
     * User text template — filled by caller with image URL.
     *
     * @param imageUrl URL of the image to analyze
     * @return complete prompt to send to the model
     */
    fun userPrompt(imageUrl: String): String =
        "Analyze this image for antique identification. Return the result as JSON.\n\nImage URL: $imageUrl"

    /**
     * Complete prompt with locale prefix prepended.
     *
     * @param imageUrl  URL of the image to analyze
     * @param lang      ISO 639-1 language code (nullable)
     * @param country   ISO 3166-1 alpha-2 country code (nullable)
     * @param currency  ISO 4217 currency code (nullable)
     * @return full prompt ready to send to the model
     */
    fun completePrompt(
        imageUrl: String,
        lang: String? = null,
        country: String? = null,
        currency: String? = null,
    ): String {
        val prefix = localeInstruction(lang, country, currency)
        return "$prefix\n\n$SYSTEM_TEXT\n\n${userPrompt(imageUrl)}"
    }
}

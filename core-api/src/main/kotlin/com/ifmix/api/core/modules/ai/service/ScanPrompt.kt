package com.ifmix.api.core.modules.ai.service

/**
 * Prompt template untuk AI antique scanning.
 *
 * System prompt menginstruksikan model agar selalu mengembalikan JSON
 * yang sesuai dengan schema ScanResult (snake_case).
 */
object ScanPrompt {

    /**
     * System text — instruksi permanen untuk semua permintaan.
     *
     * Model diinstruksikan:
     * - Selalu respond dalam format JSON valid
     * - Gunakan snake_case untuk semua key
     * - Isi semua field yang bisa dideteksi, sisanya null
     * - Tidak boleh menyertakan penjelasan di luar JSON
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

        OUTPUT SCHEMA:
        - scan_id: string (UUID or identifier)
        - status: "COMPLETED" | "FAILED"
        - is_antique: boolean
        - name: string or null
        - category: string or null
        - sub_category: string or null
        - dynasty: string or null
        - year_from: number or null
        - year_to: number or null
        - material: string or null
        - technique: string or null
        - shape: string or null
        - colors: array of strings
        - decorations: array of strings
        - has_inscription: boolean or null
        - inscription: string or null
        - height_cm: number or null
        - width_cm: number or null
        - weight_g: number or null
        - condition: string or null
        - flaws: array of strings
        - authenticity: string or null
        - authenticity_confidence: number or null
        - price_range: string or null
        - price_min: number or null
        - price_max: number or null
        - score: number or null
        - confidence: number or null
        - tags: array of strings
        - notes: string or null
        - error_message: string or null
    """.trimIndent()

    /**
     * User text template — diisi oleh caller dengan URL gambar.
     *
     * @param imageUrl URL gambar yang akan dianalisis
     * @return prompt lengkap untuk dikirim ke model
     */
    fun userPrompt(imageUrl: String): String =
        "Analyze this image for antique identification. Return the result as JSON.\n\nImage URL: $imageUrl"
}

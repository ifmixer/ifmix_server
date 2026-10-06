package com.ifmix.core.api.infra.auth

/**
 * locale 归一（BCP 47 → 受支持集）。
 *
 * 支持集（10 种）：en, zh-CN, zh-TW, ja, fr, es, pt, de, it, nl。
 * 归一规则：按 language subtag 归并（en-US→en、pt-BR→pt…）；中文按 script/region 分简繁
 * （zh / zh-Hans* / zh-SG / zh-MY → zh-CN；zh-TW / zh-HK / zh-MO / zh-Hant* → zh-TW）。
 * 以后加语言只改 [Locales]。
 */
object Locales {

    /** 非中文的受支持语言：language subtag（小写）→ 规范值。 */
    private val SUPPORTED_LANGS = setOf("en", "ja", "fr", "es", "pt", "de", "it", "nl")

    /** locale 归一结果：区分「格式非法」与「合法但不支持」。 */
    sealed interface LocaleResult {
        data class Ok(val value: String) : LocaleResult
        /** 无法解析出 language subtag（垃圾输入）——格式 bug。 */
        data object Malformed : LocaleResult
        /** 合法 BCP 47 但不在支持集（如 ko、ru）——不是格式 bug。 */
        data object Unsupported : LocaleResult
    }

    /**
     * 归一任意 BCP 47 输入。识别不了区分 [LocaleResult.Malformed]（无 language subtag）
     * 与 [LocaleResult.Unsupported]（有 subtag 但不在支持集）。以后加语言改这里。
     */
    fun normalizeLocaleResult(raw: String): LocaleResult {
        val locale = try {
            java.util.Locale.forLanguageTag(raw.trim())
        } catch (_: Exception) {
            return LocaleResult.Malformed
        }
        val lang = locale.language.lowercase()
        if (lang.isEmpty()) return LocaleResult.Malformed
        if (lang == "zh") return LocaleResult.Ok(normalizeChinese(locale))
        return if (lang in SUPPORTED_LANGS) LocaleResult.Ok(lang) else LocaleResult.Unsupported
    }

    /**
     * 归一任意 BCP 47 输入到受支持集，识别不了返回 null（不区分 malformed/unsupported）。
     * 保留给不关心细分的调用方。以后加语言改 [normalizeLocaleResult]。
     */
    fun normalizeLocale(raw: String): String? =
        (normalizeLocaleResult(raw) as? LocaleResult.Ok)?.value

    /** 中文按 script/region 分简繁；裸 zh 默认简体。 */
    private fun normalizeChinese(locale: java.util.Locale): String {
        val script = locale.script // Hans / Hant（若 tag 带 script 或可从 region 推断）
        if (script.equals("Hant", ignoreCase = true)) return "zh-TW"
        if (script.equals("Hans", ignoreCase = true)) return "zh-CN"
        return when (locale.country.uppercase()) {
            "TW", "HK", "MO" -> "zh-TW"
            else -> "zh-CN" // CN / SG / MY / 空 → 简体
        }
    }
}

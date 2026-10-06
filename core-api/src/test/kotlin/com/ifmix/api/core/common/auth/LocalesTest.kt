package com.ifmix.api.core.common.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.infra.auth.Locales
import com.ifmix.core.api.infra.auth.Locales.LocaleResult
import org.junit.jupiter.api.Test

/** locale 归一规则（受支持集 + 中文简繁分拆）。原 RequestParserTest 的 locale 用例平移。 */
class LocalesTest {

    @Test fun `normalized to supported set`() {
        assertThat(Locales.normalizeLocale("zh-cn")).isEqualTo("zh-CN")
        assertThat(Locales.normalizeLocale("en-US")).isEqualTo("en")
        assertThat(Locales.normalizeLocale("pt-BR")).isEqualTo("pt")
        assertThat(Locales.normalizeLocale("ja-JP")).isEqualTo("ja")
    }

    @Test fun `chinese simplified vs traditional`() {
        // 简体
        assertThat(Locales.normalizeLocale("zh")).isEqualTo("zh-CN")
        assertThat(Locales.normalizeLocale("zh-Hans")).isEqualTo("zh-CN")
        assertThat(Locales.normalizeLocale("zh-SG")).isEqualTo("zh-CN")
        // 繁体
        assertThat(Locales.normalizeLocale("zh-TW")).isEqualTo("zh-TW")
        assertThat(Locales.normalizeLocale("zh-HK")).isEqualTo("zh-TW")
        assertThat(Locales.normalizeLocale("zh-Hant-HK")).isEqualTo("zh-TW")
    }

    @Test fun `unsupported returns null (not throw)`() {
        assertThat(Locales.normalizeLocale("ko")).isNull()
        assertThat(Locales.normalizeLocale("ru-RU")).isNull()
    }

    @Test fun `malformed vs unsupported distinction`() {
        // 无法解析出 language subtag → Malformed（格式 bug）
        assertThat(Locales.normalizeLocaleResult("!!bad")).isEqualTo(LocaleResult.Malformed)
        // 合法 BCP 47 但不支持 → Unsupported（不是格式 bug）
        assertThat(Locales.normalizeLocaleResult("ru-RU")).isEqualTo(LocaleResult.Unsupported)
    }
}

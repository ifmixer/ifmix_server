package com.ifmix.core.api.modules.project

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.api.entity.project.ConfigPlatforms
import com.ifmix.core.api.infra.redis.CacheAside
import com.ifmix.core.api.modules.project.handler.CloudConfigItemAggHandler
import com.ifmix.core.api.modules.project.handler.CloudConfigItemAggHandler.RowSnapshot
import com.ifmix.core.api.modules.project.repo.CloudConfigItemRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * 云配置装配纯函数：keys 过滤 / 平台覆盖 / configVersion / value 解析 / ota 门槛。
 * 不触库不触缓存（repo、cache 用 mock）；ota 门槛在 loader（缓存写入前）过滤，此处直接测该函数。
 */
class CloudConfigItemAggHandlerTest {

    private val handler = CloudConfigItemAggHandler(
        mock<CloudConfigItemRepository>(),
        mock<CacheAside>(),
        jacksonObjectMapper(),
    )

    private fun row(
        key: String,
        valueText: String,
        platform: Int = ConfigPlatforms.ALL,
        min: String? = null,
        max: String? = null,
        updatedMillis: Long = 1000L,
    ) = RowSnapshot(key, valueText, platform, min, max, updatedMillis)

    // ===== assemble =====

    @Test
    fun `returns only requested keys, configVersion over returned rows`() {
        val p = handler.assemble(
            listOf(
                row("flagA", "true", updatedMillis = 1000L),
                row("flagB", "\"x\"", updatedMillis = 3000L),
            ),
            keys = listOf("flagB"),
        )
        assertThat(p.values).isEqualTo(mapOf("flagB" to "x"))
        assertThat(p.configVersion).isEqualTo("3000")
    }

    @Test
    fun `platform scoped row overrides global row for same key`() {
        val p = handler.assemble(
            listOf(
                row("flag", "false"),
                row("flag", "true", platform = ConfigPlatforms.IOS, updatedMillis = 2000L),
            ),
            keys = listOf("flag"),
        )
        assertThat(p.values).isEqualTo(mapOf("flag" to true))
        assertThat(p.configVersion).isEqualTo("2000")
    }

    @Test
    fun `unknown or unrequested keys are absent, not an error`() {
        val p = handler.assemble(
            listOf(row("known", "true"), row("other", "1")),
            keys = listOf("known", "missing"),
        )
        assertThat(p.values).isEqualTo(mapOf("known" to true))
    }

    @Test
    fun `duplicate keys in request are deduped`() {
        val p = handler.assemble(listOf(row("a", "1")), keys = listOf("a", "a"))
        assertThat(p.values).isEqualTo(mapOf("a" to 1))
    }

    @Test
    fun `empty keys yields zero version and empty values`() {
        val p = handler.assemble(listOf(row("a", "1")), keys = emptyList())
        assertThat(p.configVersion).isEqualTo("0")
        assertThat(p.values).isEqualTo(emptyMap<String, Any>())
    }

    @Test
    fun `value json kinds parsed to plain objects`() {
        val p = handler.assemble(
            listOf(
                row("b", "true"),
                row("n", "42"),
                row("f", "1.5"),
                row("s", "\"text\""),
                row("o", "{\"k\":[1,true]}"),
                row("arr", "[\"a\",2]"),
            ),
            keys = listOf("b", "n", "f", "s", "o", "arr"),
        )
        assertThat(p.values["b"]).isEqualTo(true)
        assertThat(p.values["n"]).isEqualTo(42)
        assertThat(p.values["f"]).isEqualTo(1.5)
        assertThat(p.values["s"]).isEqualTo("text")
        assertThat(p.values["o"]).isEqualTo(mapOf("k" to listOf(1, true)))
        assertThat(p.values["arr"]).isEqualTo(listOf("a", 2))
    }

    // ===== matchesOtaGate（loader 内、进缓存前调用）=====

    @Test
    fun `min gate excludes older otaVersion`() {
        assertThat(handler.matchesOtaGate(row("a", "1", min = "1-23-0"), "1-22-9")).isFalse()
        assertThat(handler.matchesOtaGate(row("a", "1", min = "1-23-0"), "1-23-0")).isTrue()
        assertThat(handler.matchesOtaGate(row("a", "1", min = "1-23-0"), "1-24-0")).isTrue()
    }

    @Test
    fun `max gate excludes newer otaVersion`() {
        assertThat(handler.matchesOtaGate(row("a", "1", max = "1-23-0"), "1-24-0")).isFalse()
        assertThat(handler.matchesOtaGate(row("a", "1", max = "1-23-0"), "1-23-0")).isTrue()
    }

    @Test
    fun `null otaVersion only passes ungated rows`() {
        assertThat(handler.matchesOtaGate(row("a", "1"), null)).isTrue()
        assertThat(handler.matchesOtaGate(row("a", "1", min = "1-23-0"), null)).isFalse()
        assertThat(handler.matchesOtaGate(row("a", "1", max = "1-23-0"), null)).isFalse()
    }
}

package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.api.dto.ai.ScanInput
import com.ifmix.core.api.dto.ai.ScanMediaItem
import com.ifmix.core.api.dto.ai.ScanType
import com.ifmix.core.api.modules.ai.service.ScanPromptProperties
import com.ifmix.core.api.modules.ai.service.ScanPromptRegistry
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 按 projectId 选提示词组（设计 §8.1）：toy 与 tcgcard 同构（key 是 projectId「toy」，文件名前缀仍是 toyscan-scan-system） —— basic 组仅占位，
 * 普通扫描直接走 premium（basic+premium 一次产出）。
 */
class ScanPromptRegistryTest {

    private val toyProps = ScanPromptProperties(
        default = "antique",
        projects = mapOf(
            "antique" to ScanPromptProperties.ProjectPromptProperties(
                name = "antique-scan-system",
                version = "v10",
            ),
            "toy" to ScanPromptProperties.ProjectPromptProperties(
                name = "toyscan-scan-system",
                full = "toyscan-scan-system-premium",
                version = "v1",
                scanUsesFullTemplate = true,
            ),
        ),
    )

    // projectId 不在 ScanInput 里：runner 从 ctx.projectId 取提示词组（见 7e336dce）。
    private fun input(type: ScanType) = ScanInput(
        scanId = UUID.randomUUID(),
        items = listOf(ScanMediaItem(imageUrl = "https://x/1.jpg", mediaType = "image/jpeg")),
        type = type,
    )

    @Test
    fun `toy resolves to v1 and basic scans use the full template`() {
        val prompt = ScanPromptRegistry(toyProps).forProject("toy")

        assertThat(prompt.promptVersion).isEqualTo("v1")
        assertThat(prompt.basicScanUsesFullTemplate).isTrue()
        // BASIC 走 premium 模板 → 产出里带 premium_result（premium 组是 basic+premium 一次产出）
        assertThat(prompt.systemPromptFor(input(ScanType.BASIC))).contains("premium_result")
        assertThat(prompt.systemPromptFor(input(ScanType.BASIC)))
            .isEqualTo(prompt.fullSystemPrompt(input(ScanType.BASIC)))
    }

    @Test
    fun `unknown project falls back to the default group`() {
        val prompt = ScanPromptRegistry(toyProps).forProject("no-such-app")

        assertThat(prompt.promptVersion).isEqualTo("v10")
        assertThat(prompt.basicScanUsesFullTemplate).isFalse()
    }

    @Test
    fun `missing config keeps the legacy behavior`() {
        val prompt = ScanPromptRegistry(ScanPromptProperties()).forProject(null)

        assertThat(prompt.promptVersion).isEqualTo("v10")
        assertThat(prompt.basicScanUsesFullTemplate).isFalse()
    }
}

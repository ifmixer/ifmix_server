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
 * 按 projectId 选提示词组（设计 §8.1）：toy 与 tcgcard 同构（key 是 projectId「toy」，文件名前缀仍是 toyscan-scan-system）。
 * 2026-10-30 起五个新 app（tcg/rock/coin/toy/note）与 antique 统一为两段式：
 * basic 扫描只出 basic_result（basic 组），premium 由用户主动发起的 DeepResearch 异步任务产出（full 组）。
 * note 同构。
 *
 * 这里带上了真实的 classpath 文件加载：配置声明了 full 组而 premium 模板文件缺失时，
 * 该项目首次扫描会在懒加载时炸（2026-10-30 note premium 文件未提交导致
 * `Classpath resource not found: prompts/note-scan-system-premium_v1.md`）——此用例是防回归闸门。
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
                scanUsesFullTemplate = false,
            ),
            "note" to ScanPromptProperties.ProjectPromptProperties(
                name = "note-scan-system",
                full = "note-scan-system-premium",
                version = "v1",
                scanUsesFullTemplate = false,
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
    fun `toy resolves to v1 and basic scans use the basic-only template`() {
        val prompt = ScanPromptRegistry(toyProps).forProject("toy")

        assertThat(prompt.promptVersion).isEqualTo("v1")
        assertThat(prompt.basicScanUsesFullTemplate).isFalse()
        // BASIC 走 basic 组 → 只有 basic_result；同时证明 basic 模板文件能从 classpath 加载
        assertThat(prompt.systemPromptFor(input(ScanType.BASIC)))
            .isEqualTo(prompt.systemPrompt(input(ScanType.BASIC)))
        assertThat(prompt.systemPromptFor(input(ScanType.BASIC))).contains("basic_result")
        // DEEP_RESEARCH 走 full 组 → 产出里带 premium_result；同时证明 premium 模板文件能加载（防回归闸门）
        assertThat(prompt.systemPromptFor(input(ScanType.DEEP_RESEARCH)))
            .isEqualTo(prompt.fullSystemPrompt(input(ScanType.DEEP_RESEARCH)))
        assertThat(prompt.systemPromptFor(input(ScanType.DEEP_RESEARCH))).contains("premium_result")
    }

    @Test
    fun `note resolves to v1 and basic scans use the basic-only template`() {
        val prompt = ScanPromptRegistry(toyProps).forProject("note")

        assertThat(prompt.promptVersion).isEqualTo("v1")
        assertThat(prompt.basicScanUsesFullTemplate).isFalse()
        // 同时验证 basic / premium 两个模板文件都能从 classpath 加载（见类注释的防回归说明）
        assertThat(prompt.systemPromptFor(input(ScanType.BASIC))).contains("basic_result")
        assertThat(prompt.systemPromptFor(input(ScanType.DEEP_RESEARCH))).contains("premium_result")
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

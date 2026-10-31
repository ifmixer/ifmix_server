package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.ai.ScanInput
import com.ifmix.core.api.dto.ai.ScanMediaItem
import com.ifmix.core.api.dto.ai.ScanType
import com.ifmix.core.api.entity.ai.ImageCategories
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * userPrompt 的视角说明行（设计 §8.2）：
 * 全部 MAIN → 通用措辞；MAIN 混合其他分类 → MAIN 标 primary photo；缺分类/未知码 → 通用措辞。
 */
class ScanPromptUserPromptTest {
    private val prompt = ScanPrompt(
        promptVersion = "v1",
        basicName = "toyscan-scan-system-basic",
        fullName = "toyscan-scan-system-premium",
        basicScanUsesFullTemplate = true,
    )

    private fun input(vararg categories: Int?) = ScanInput(
        scanId = UUID.randomUUID(),
        items = categories.map { ScanMediaItem(imageUrl = "https://x/$it", mediaType = "image/jpeg", category = it) },
        type = ScanType.BASIC,
    )

    @Test
    fun `single main image keeps the legacy wording`() {
        assertThat(prompt.userPrompt(input(ImageCategories.MAIN))).isEqualTo("Analyze the attached image. Respond in en-US; all user-facing text must use that language.")
    }

    @Test
    fun `all main images keep the legacy wording`() {
        assertThat(
            prompt.userPrompt(input(ImageCategories.MAIN, ImageCategories.MAIN, ImageCategories.MAIN)),
        ).isEqualTo(
            "Analyze the 3 attached images as views of the same primary object unless the visual evidence clearly indicates otherwise. " +
                "Respond in en-US; all user-facing text must use that language.",
        )
    }

    @Test
    fun `mixed main and back labels main as primary photo`() {
        assertThat(
            prompt.userPrompt(input(ImageCategories.MAIN, ImageCategories.BACK)),
        ).isEqualTo(
            "Analyze the 2 attached images as views of the same primary object unless the visual evidence clearly indicates otherwise. " +
                "Image 1 = primary photo, Image 2 = back. Respond in en-US; all user-facing text must use that language.",
        )
    }

    @Test
    fun `front and back use their view labels`() {
        assertThat(
            prompt.userPrompt(input(ImageCategories.FRONT, ImageCategories.BACK, ImageCategories.BOTTOM)),
        ).isEqualTo(
            "Analyze the 3 attached images as views of the same primary object unless the visual evidence clearly indicates otherwise. " +
                "Image 1 = front, Image 2 = back, Image 3 = bottom. Respond in en-US; all user-facing text must use that language.",
        )
    }

    @Test
    fun `unknown or missing category falls back to the legacy wording`() {
        assertThat(prompt.userPrompt(input(1000))).isEqualTo("Analyze the attached image. Respond in en-US; all user-facing text must use that language.")
        assertThat(prompt.userPrompt(input(null, ImageCategories.BACK)))
            .isEqualTo(
                "Analyze the 2 attached images as views of the same primary object unless the visual evidence clearly indicates otherwise. " +
                    "Respond in en-US; all user-facing text must use that language.",
            )
    }

    @Test
    fun `single front image gets a view line`() {
        assertThat(prompt.userPrompt(input(ImageCategories.FRONT))).isEqualTo("Analyze the attached image. Image 1 = front. Respond in en-US; all user-facing text must use that language.")
    }
}

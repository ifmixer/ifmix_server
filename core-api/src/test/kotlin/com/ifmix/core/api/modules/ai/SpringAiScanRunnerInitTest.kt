package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotNull
import com.ifmix.core.api.modules.ai.service.AiApiKeyStore
import com.ifmix.core.api.modules.ai.service.AiChatClientFactory
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import com.ifmix.core.api.modules.ai.service.SpringAiScanRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper

/**
 * scan-deadline 与 call-timeout 的配置约束：deadline ≤ call-timeout 时启动即失败——
 * 否则每次扫描都会在首次尝试前被预算检查拦下，静默全量 AI_UNAVAILABLE 且无从排查。
 */
class SpringAiScanRunnerInitTest {

    private fun factory(callTimeoutSec: Long): AiChatClientFactory {
        val factory = mock<AiChatClientFactory>()
        whenever(factory.callTimeoutSec).doReturn(callTimeoutSec)
        return factory
    }

    private fun buildRunner(callTimeoutSec: Long, scanDeadlineSec: Long): SpringAiScanRunner =
        SpringAiScanRunner(
            chatClientFactory = factory(callTimeoutSec),
            keyStore = mock<AiApiKeyStore>(),
            fallbackOrderStr = "",
            cooldownRateLimitedSec = 300,
            cooldownInvalidKeySec = 3600,
            cooldownTimeoutSec = 300,
            cooldownOtherSec = 30,
            scanDeadlineSec = scanDeadlineSec,
            snakeCaseMapper = mock<ObjectMapper>(),
            scanPrompt = mock<ScanPrompt>(),
        )

    @Test
    fun `deadline not exceeding call timeout fails at startup`() {
        val err = assertThrows<IllegalArgumentException> {
            buildRunner(callTimeoutSec = 360, scanDeadlineSec = 300)
        }
        assertThat(err.message ?: "").contains("must exceed")
    }

    @Test
    fun `deadline exceeding call timeout constructs fine`() {
        assertThat(buildRunner(callTimeoutSec = 360, scanDeadlineSec = 600)).isNotNull()
    }
}

package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.ai.ScanInput
import com.ifmix.core.api.dto.ai.ScanMediaItem
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.ai.service.AiApiKeyStore
import com.ifmix.core.api.modules.ai.service.AiChatClientFactory
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import com.ifmix.core.api.modules.ai.service.SpringAiScanRunner
import com.openai.core.http.Headers
import com.openai.errors.UnauthorizedException
import com.openai.models.ErrorObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.prompt.Prompt
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 类型化 401/403 → 永久禁用 + message 兜底 → 只冷却的 runner 侧行为（设计见
 * docs/superpowers/specs/2026-10-04-ai-key-disable-and-probe-skip-design.md）。
 * mock ChatClient.prompt().call() 按分支抛出类型化/兜底异常，断言 disableKey 的调用与否；
 * disableKey 本身抛任意异常时主流程不中断（runner 对禁用 seam 无返回值依赖）。
 *
 * 注意 stub 一律用 doReturn/thenAnswer 形式：spec.call() 的默认桩会抛异常，
 * 若用 whenever(spec.call()) 会在重 stub 时真的调用一次、把异常抛到测试外。
 */
class SpringAiScanRunnerDisableTest {

    private class Fixture {
        val keyStore = mock<AiApiKeyStore>()
        val factory = mock<AiChatClientFactory>()
        val chatClient = mock<ChatClient>()
        val spec = mock<ChatClientRequestSpec>()
        val callSpec = mock<CallResponseSpec>()
        val mapper = mock<ObjectMapper>()
        val scanPrompt = mock<ScanPrompt>()
        val runner: SpringAiScanRunner
        val doc = AiApiKeyStore.AiApiKeyDoc("k-1", "sk-x")
        val input = ScanInput(scanId = UUID.randomUUID(), items = listOf(ScanMediaItem("https://x/a.jpg", "image/jpeg")))
        val ctx = ActionContext(projectId = "p")
        /** 默认失败链用的类型化 401 异常（headers 必填，error 给一个 message 便于排查）。 */
        val unauth = UnauthorizedException.builder()
            .headers(Headers.builder().build())
            .error(ErrorObject.builder().message("401 Unauthorized").build())
            .build()

        init {
            doReturn(spec).`when`(chatClient).prompt(any<Prompt>())
            doReturn(chatClient).`when`(factory).forKey(any(), any())
            doReturn(360L).`when`(factory).callTimeoutSec
            doReturn("m").`when`(factory).defaultModel
            doReturn(false).`when`(keyStore).isEmpty()
            doReturn(doc).`when`(keyStore).pick()
            doReturn(mapOf<String, Any>("ok" to true)).`when`(mapper).readValue(any(), any())
            doReturn("sys").`when`(scanPrompt).systemPrompt(input)
            doReturn("user").`when`(scanPrompt).userPrompt(input)
            doAnswer { unauth }.`when`(spec).call()
            runner = SpringAiScanRunner(
                chatClientFactory = factory,
                keyStore = keyStore,
                fallbackOrderStr = "",
                cooldownRateLimitedSec = 300,
                cooldownInvalidKeySec = 3600,
                cooldownTimeoutSec = 300,
                cooldownOtherSec = 30,
                scanDeadlineSec = 600,
                snakeCaseMapper = mapper,
                scanPrompt = scanPrompt,
            )
        }
    }

    @Test
    fun `typed 401 disables the key and still marks cooldown`() {
        val fx = Fixture()
        val err = assertThrows<ApiError> { fx.runner.run(fx.ctx, fx.input) }
        // 401 后同 key 恒 401，全部 attempt 耗尽 → AI_UNAVAILABLE
        assertThat(err.errorCode).isEqualTo(ErrorCode.AI_UNAVAILABLE)
        // 每次 attempt 都：标记冷却 + 永久禁用
        verify(fx.keyStore, atLeastOnce()).markCooldown(fx.doc.id, 3600L, "401")
        verify(fx.keyStore, atLeastOnce()).disableKey(fx.doc.id)
    }

    @Test
    fun `message fallback 401 only cools down, never disables`() {
        // 网关自定义错误页 / SDK 包装 message 误中的兜底路径：仅冷却，不触发不可逆的禁用
        val fx = Fixture()
        doAnswer { throw IllegalStateException("HTTP unauthorized: invalid api key") }.`when`(fx.spec).call()
        assertThrows<ApiError> { fx.runner.run(fx.ctx, fx.input) }
        verify(fx.keyStore, atLeastOnce()).markCooldown(fx.doc.id, 3600L, "401")
        verify(fx.keyStore, never()).disableKey(any())
    }

    @Test
    fun `disableKey throwing any exception does not interrupt the run`() {
        // 禁用是 best-effort 副作用（AiConfig 侧 catch Exception 收敛）；即便 seam 本身抛出，
        // runner 也不依赖其返回值——主流程照常走完全部 attempt 后以 AI_UNAVAILABLE 收敛。
        val fx = Fixture()
        doAnswer { throw RuntimeException("boom") }.doReturn(Unit).`when`(fx.keyStore).disableKey(any())
        val err = assertThrows<ApiError> { fx.runner.run(fx.ctx, fx.input) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.AI_UNAVAILABLE)
        verify(fx.keyStore, atLeastOnce()).disableKey(fx.doc.id)
        verify(fx.keyStore, atLeastOnce()).markCooldown(fx.doc.id, 3600L, "401")
    }

    @Test
    fun `success path never disables or cools`() {
        val fx = Fixture()
        doReturn(fx.callSpec).`when`(fx.spec).call()
        doReturn("""{"ok":true}""").`when`(fx.callSpec).content()
        val result = fx.runner.run(fx.ctx, fx.input)
        assertThat(result).isEqualTo(mapOf("ok" to true))
        verify(fx.keyStore, never()).disableKey(any())
        verify(fx.keyStore, never()).markCooldown(any(), any(), any())
    }
}

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
import com.openai.errors.BadRequestException
import com.openai.models.ErrorObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.prompt.Prompt
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * provider 回源取图失败（400 + "loading IMAGE data … Timed out while downloading media URL"）的重试行为。
 *
 * 这条路径的关键取舍：**它是瞬态错误，不是坏图片也不是坏 key**——
 * 所以①不冷却 key（key 没错）；②只有限重试（[SpringAiScanRunner] 内部上界 2），
 * 不让 provider 的图片下载超时把 600s deadline 耗光；③真坏图片的 400 维持原语义（立即终止）。
 *
 * stub 形式沿用 SpringAiScanRunnerDisableTest 的约定：`spec.call()` 默认桩会抛异常，
 * 一律用 doReturn/doAnswer，避免重 stub 时真的调用一次把异常抛到测试外。
 */
class SpringAiScanRunnerImageFetchRetryTest {

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
        val input = ScanInput(
            scanId = UUID.randomUUID(),
            items = listOf(ScanMediaItem("https://u1.example.com/a.jpg", "image/jpeg")),
        )
        val ctx = ActionContext(projectId = "p")

        /** 生产实测的 provider 回源取图失败报文（agnes-3.0-flash）。 */
        val imageFetchFailure: BadRequestException = BadRequestException.builder()
            .headers(Headers.builder().build())
            .error(
                ErrorObject.builder()
                    .code("invalid_request_error")
                    .message(
                        "An exception occurred while loading IMAGE data at index 0: " +
                            "Error while loading data ImageData(url='https://u1.example.com/a.jpg') " +
                            "Timed out while downloading media URL: https://u1.example.com/a.jpg"
                    )
                    .param("image").type("invalid_request_error").build()
            )
            .build()

        /** 真坏图片：同样是 400，但不涉及"下载"，必须维持立即终止的语义。 */
        val genuinelyBadImage: BadRequestException = BadRequestException.builder()
            .headers(Headers.builder().build())
            .error(
                ErrorObject.builder()
                    .code("invalid_image_format")
                    .message("Unsupported image format: image/webp")
                    .param("image").type("invalid_request_error").build()
            )
            .build()

        val callCount = AtomicInteger(0)

        init {
            doReturn(spec).`when`(chatClient).prompt(any<Prompt>())
            doReturn(chatClient).`when`(factory).forKey(any(), any())
            doReturn(360L).`when`(factory).callTimeoutSec
            doReturn("m").`when`(factory).defaultModel
            doReturn(false).`when`(keyStore).isEmpty()
            doReturn(doc).`when`(keyStore).pick()
            val okResult: Map<String, Any> = mapOf("ok" to true)
            doReturn(okResult).`when`(mapper).readValue(any<String>(), any<Class<Any>>())
            doReturn("sys").`when`(scanPrompt).systemPrompt(input)
            doReturn("user").`when`(scanPrompt).userPrompt(input)
            doReturn(callSpec).`when`(spec).call()
            doReturn("{\"ok\":true}").`when`(callSpec).content()
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

        /** 让前 [failures] 次调用抛 [failure]，之后正常返回。 */
        fun failFirst(failures: Int, failure: Exception) {
            doAnswer {
                if (callCount.incrementAndGet() <= failures) throw failure
                callSpec
            }.`when`(spec).call()
        }
    }

    @Test
    fun `image fetch failure is retried and succeeds on another key`() {
        val f = Fixture().apply { failFirst(1, imageFetchFailure) }

        val result = f.runner.run(f.ctx, f.input)

        assertThat(result).isEqualTo(mapOf<String, Any?>("ok" to true))
        assertThat(f.callCount.get()).isEqualTo(2)
        // 换 key 重试：不应惩罚这个 key——既没有冷却也没有禁用
        verify(f.keyStore, never()).markCooldown(any(), any(), any())
        verify(f.keyStore, never()).disableKey(any())
    }

    @Test
    fun `image fetch failure beyond the retry bound fails without cooldown`() {
        // 每次都失败 → 重试到上界后放弃（异常收敛为 INVALID_REQUEST，不再是 AI_UNAVAILABLE）
        val f = Fixture().apply { failFirst(Int.MAX_VALUE, imageFetchFailure) }

        val error = assertThrows<ApiError> { f.runner.run(f.ctx, f.input) }

        assertThat(error.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(error.message).isEqualTo("AI could not load the image (upstream fetch failed)")
        assertThat(f.callCount.get()).isEqualTo(2) // 上界 2，不是 MAX_ATTEMPTS_PER_MODEL(4)
        verify(f.keyStore, never()).markCooldown(any(), any(), any())
    }

    @Test
    fun `genuinely bad image 400 is still terminal and not retried`() {
        val f = Fixture().apply { failFirst(Int.MAX_VALUE, genuinelyBadImage) }

        val error = assertThrows<ApiError> { f.runner.run(f.ctx, f.input) }

        assertThat(error.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(error.message).isEqualTo("AI rejected the request (invalid image or input)")
        assertThat(f.callCount.get()).isEqualTo(1) // 坏图片不重试，一次即止
        verify(f.keyStore, never()).disableKey(any())
    }

    @Test
    fun `image fetch phrase alone does not hijack a non-400 error`() {
        // 没有类型化 400/422（比如超时的 SocketException 里带了 media URL 字样）不应被当成图片拉取失败，
        // 否则会绕过正常的冷却/分类路径。
        val f = Fixture().apply { failFirst(Int.MAX_VALUE, RuntimeException("Timed out while downloading media URL")) }

        assertThrows<ApiError> { f.runner.run(f.ctx, f.input) }

        // 走正常分类路径：timeout → 冷却（区别于图片拉取失败的"不冷却"）
        verify(f.keyStore, times(4)).markCooldown(any(), any(), any())
    }
}

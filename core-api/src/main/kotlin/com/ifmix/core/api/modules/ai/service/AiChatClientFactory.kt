package com.ifmix.core.api.modules.ai.service

import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 API key + model 动态提供 ChatClient（provider 无关的 OpenAI-compatible 客户端工厂）。
 *
 * 底层 OpenAI client 按 apiKey 缓存复用（连接池/线程池昂贵，逐次新建会泄漏）；
 * model 由 OpenAiChatOptions 每次调用指定，与底层 client 无关。
 * 统一配置调用超时并关闭 SDK 内置重试——外层已有换 key 重试，叠加会放大 AI 成本。
 */
@Component
open class AiChatClientFactory(
    @Value("\${spring.ai.openai.base-url:https://api.openai.com}") val baseUrl: String,
    @Value("\${spring.ai.openai.chat.options.model:gpt-4o}") val defaultModel: String,
    /** 单次调用超时（秒）。公开供 runner 的总预算检查使用（剩余时间不足一次调用时不发起新 attempt）。 */
    @Value("\${app.ai.call-timeout-sec:360}") val callTimeoutSec: Long,
    /**
     * 单次调用的显式输出 token 上限（设计 §8.2.1）。
     *
     * 不设会走 provider 默认值：一次调用同时产出 basic + premium 时输出接近翻倍，
     * 超过默认上限 → JSON 被截断 → 整单失败（basic_result 本来是好的）。
     * 显式设定后配合 [SpringAiScanRunner.isLikelyTruncatedJson] 的降级重跑。
     */
    @Value("\${app.ai.max-output-tokens:8192}") private val maxOutputTokens: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val callTimeout: Duration = Duration.ofSeconds(callTimeoutSec)
    private val clients = ConcurrentHashMap<String, ClientPair>()

    private class ClientPair(val sync: OpenAIClient, val async: OpenAIClientAsync) : AutoCloseable {
        override fun close() {
            (sync as? AutoCloseable)?.close()
            (async as? AutoCloseable)?.close()
        }
    }

    open fun forKey(apiKey: String, model: String = defaultModel): ChatClient {
        val pair = clients.computeIfAbsent(apiKey) { key ->
            log.debug("Creating OpenAI clients: baseUrl={}, key={}..., timeout={}s", baseUrl, key.take(8), callTimeout.seconds)
            val syncClient: OpenAIClient = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .timeout(callTimeout)
                .maxRetries(0)
                .build()
            val asyncClient: OpenAIClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .timeout(callTimeout)
                .maxRetries(0)
                .build()
            ClientPair(syncClient, asyncClient)
        }

        val chatModel = OpenAiChatModel.builder()
            .openAiClient(pair.sync)
            .openAiClientAsync(pair.async)
            .options(
                OpenAiChatOptions.builder()
                    .model(model)
                    .maxTokens(maxOutputTokens)
                    .responseFormat(
                        OpenAiChatModel.ResponseFormat.builder()
                            .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                            .build()
                    )
                    .build()
            )
            .build()

        return ChatClient.builder(chatModel).build()
    }

    @PreDestroy
    fun destroy() {
        clients.values.forEach { pair -> runCatching { pair.close() } }
        clients.clear()
    }
}

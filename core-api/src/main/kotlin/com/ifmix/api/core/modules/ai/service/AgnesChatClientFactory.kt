package com.ifmix.api.core.modules.ai.service

import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 按 API key + model 动态创建 ChatClient（每次 forKey 产生新实例，无状态，供多 key 轮换）。
 */
@Component
open class AgnesChatClientFactory(
    @Value("\${spring.ai.openai.base-url:https://api.openai.com}") val baseUrl: String,
    @Value("\${spring.ai.openai.chat.options.model:gpt-4o}") val defaultModel: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    open fun forKey(apiKey: String, model: String = defaultModel): ChatClient {
        log.debug("Creating ChatClient: baseUrl={}, model={}, apiKey={}...", baseUrl, model, apiKey.take(8))

        val syncClient: OpenAIClient = OpenAIOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build()

        val asyncClient: OpenAIClientAsync = OpenAIOkHttpClientAsync.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build()

        val chatModel = OpenAiChatModel.builder()
            .openAiClient(syncClient)
            .openAiClientAsync(asyncClient)
            .options(
                OpenAiChatOptions.builder()
                    .model(model)
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
}

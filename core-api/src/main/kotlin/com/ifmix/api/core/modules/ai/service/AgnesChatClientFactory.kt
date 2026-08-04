package com.ifmix.api.core.modules.ai.service

import com.openai.client.OpenAIClientImpl
import com.openai.core.ClientOptions
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient

/**
 * 按 API key + model 动态创建 ChatClient（每次 forKey 产生新实例，无状态，供多 key 轮换）。
 *
 * Spring AI 2.0：OpenAI 集成改用官方 openai-java SDK 的 [com.openai.client.OpenAIClient]。
 * 手动构造 per-key client：SpringAiOpenAiHttpClient（Spring AI 的 okhttp 传输）+ ClientOptions
 * （注入 baseUrl/apiKey）→ OpenAIClientImpl → OpenAiChatModel。
 */
open class AgnesChatClientFactory(
    val baseUrl: String,
    val defaultModel: String,
) {

    open fun forKey(apiKey: String, model: String = defaultModel): ChatClient {
        val httpClient = SpringAiOpenAiHttpClient.builder().build()
        val clientOptions = ClientOptions.builder()
            .httpClient(httpClient)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build()
        val openAiClient = OpenAIClientImpl(clientOptions)

        val chatModel = OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .options(OpenAiChatOptions.builder().model(model).build())
            .build()

        return ChatClient.builder(chatModel).build()
    }
}

package com.ifmix.api.core.common.ai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi

/**
 * Factory untuk membuat ChatClient dengan API key + model yang dinamis.
 *
 * Setiap call ke forKey() menghasilkan instance baru (stateless),
 * sehingga bisa dipakai untuk rotasi key tanpa konflik.
 */
open class AgnesChatClientFactory(
    val baseUrl: String,
    val defaultModel: String,
) {

    /**
     * Buat ChatClient baru untuk key + model tertentu.
     *
     * @param apiKey  API key yang akan dipakai saat runtime
     * @param model   Nama model (default dari factory config)
     * @return ChatClient yang siap dipanggil
     */
    open fun forKey(apiKey: String, model: String = defaultModel): ChatClient {
        val api = OpenAiApi.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .build()

        val chatModel = OpenAiChatModel.builder().apply {
            openAiApi(api)
            defaultOptions(OpenAiChatOptions.builder()
                .model(model)
                .build())
        }.build()

        return ChatClient.builder(chatModel).build()
    }
}

package com.ifmix.api.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(
    exclude = [
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration::class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration::class,
    ],
)
class CoreApplication

fun main(args: Array<String>) {
    runApplication<CoreApplication>(*args)
}

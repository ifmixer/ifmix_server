package com.ifmix.api.core.common.ai

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import com.ifmix.api.core.common.ai.repo.AgnesKeyRepo

/**
 * AI configuration — assembly semua bean untuk Agnes AI scanning.
 *
 * Diaktifkan hanya jika app.storage.type=s3 (sama dengan AntiqueConfig).
 * Mengganti StubScanRunner dengan SpringAiScanRunner (@Primary).
 */
@Configuration
@ConditionalOnProperty(
    name = arrayOf("app.storage.type"),
    havingValue = "s3",
    matchIfMissing = false,
)
class AiConfig {

    @Bean
    @Primary
    fun agnesKeyStore(
        redis: StringRedisTemplate,
        mongo: MongoTemplate,
    ): AgnesKeyStore {
        return AgnesKeyStore(
            redis = redis,
            loadKeys = {
                val repo = AgnesKeyRepo(mongo)
                repo.loadEnabled()
            },
        )
    }

    @Bean
    fun agnesChatClientFactory(
        @Value("\${spring.ai.openai.base-url:https://api.openai.com}") baseUrl: String,
        @Value("\${spring.ai.openai.chat.options.model:gpt-4o}") defaultModel: String,
    ): AgnesChatClientFactory {
        return AgnesChatClientFactory(baseUrl, defaultModel)
    }

    @Bean
    @Primary
    fun springAiScanRunner(
        factory: AgnesChatClientFactory,
        store: AgnesKeyStore,
        @Value("\${app.agnes.ai.modelFallbackOrder:llama-4.0-mini,miro-4}") fallbackOrder: String,
    ): SpringAiScanRunner {
        val models = fallbackOrder.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return SpringAiScanRunner(factory, store, models)
    }
}

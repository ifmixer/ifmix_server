package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.entity.enums.AgnesKeyType
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.modules.ai.repo.AgnesKeyRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

/**
 * AI configuration — assembly semua bean untuk Agnes AI scanning.
 *
 * Diaktifkan hanya jika app.storage.type=s3 (sama dengan AntiqueConfig).
 * Mengganti StubScanRunner dengan SpringAiScanRunner (@Primary).
 */
@Configuration
@ConditionalOnProperty(
    name = ["app.storage.type"],
    havingValue = "s3",
    matchIfMissing = false,
)
class AiConfig {

    @Bean
    @Primary
    fun agnesKeyStore(
        redis: StringRedisTemplate,
        agnesKeyRepo: AgnesKeyRepository,
    ): AgnesKeyStore {
        return AgnesKeyStore(
            redis = redis,
            loadKeys = {
                val ctx = RepoContext.DEFAULT
                agnesKeyRepo.findAllEnabled(ctx).map { key ->
                    AgnesKeyStore.AgnesKeyDoc(
                        id = key.id.toString(),
                        key = key.key ?: "",
                        type = AgnesKeyType.fromCode(key.type).name,
                        rateLimit = key.rateLimit,
                        windowSec = key.windowSec,
                        models = key.models,
                    )
                }
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
        @Qualifier("snakeCaseMapper") snakeCaseMapper: ObjectMapper,
    ): SpringAiScanRunner {
        val models = fallbackOrder.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return SpringAiScanRunner(factory, store, models, snakeCaseMapper)
    }
}

package com.ifmix.api.core.common.modules.ai.service

import com.ifmix.api.core.common.entity.enums.AgnesKeyType
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.modules.ai.repo.AgnesKeyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * AI configuration — assembly bean untuk Agnes AI scanning.
 *
 * 不再使用 ConditionalOnProperty，AgnesKeyStore / AgnesChatClientFactory 始终加载。
 * SpringAiScanRunner 已经是 @Service @Primary，不在这里手动创建。
 */
@Configuration
class AiConfig {

    @Bean
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
}

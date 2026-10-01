package com.ifmix.core.api.modules.ai.service

import com.ifmix.core.api.modules.ai.repo.AiApiKeyRepository
import com.ifmix.core.api.entity.ai.AiApiKey
import com.ifmix.core.api.entity.ai.ApiProviders
import com.ifmix.core.api.entity.ai.enabled
import com.ifmix.core.api.entity.ai.provider
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * AI 模块 bean 装配。
 *
 * AiApiKeyStore 需要 lambda 构造参数（loadKeys），不适合直接 @Component 注册，
 * 因此在此处通过 @Bean 手动装配。
 */
@Configuration
class AiConfig(
    private val sqlClient: KSqlClient,
    private val redis: StringRedisTemplate,
    private val aiApiKeyRepo: AiApiKeyRepository,
) {
    @Bean
    fun aiApiKeyStore(): AiApiKeyStore = AiApiKeyStore(redis) {
        // 用全局 sqlClient 加载 Agnes provider 的启用 key（不依赖 ModuleCtx，key 加载是 infra 级操作）。
        // 将来接新 provider 时：新 provider 的 loader 在此并列，各自按 provider 过滤。
        val keys = sqlClient.createQuery(AiApiKey::class) {
            where(table.enabled eq true)
            where(table.provider eq ApiProviders.AGNES)
            select(table)
        }.execute()
        keys.map { k ->
            AiApiKeyStore.AiApiKeyDoc(
                id = k.id.toString(),
                key = k.key,
                type = k.type.toString(),
                rateLimit = k.rateLimit,
                windowSec = k.windowSec,
                models = k.models,
            )
        }
    }
}

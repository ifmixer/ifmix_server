package com.ifmix.core.api.modules.ai.service

import com.ifmix.core.api.modules.ai.repo.AgnesKeyRepository
import org.springframework.beans.factory.annotation.Value
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * AI 模块 bean 装配。
 *
 * AgnesKeyStore 需要 lambda 构造参数（loadKeys），不适合直接 @Component 注册，
 * 因此在此处通过 @Bean 手动装配。
 */
@Configuration
class AiConfig(
    private val sqlClient: KSqlClient,
    private val redis: StringRedisTemplate,
    private val agnesKeyRepo: AgnesKeyRepository,
) {
    @Bean
    fun agnesKeyStore(): AgnesKeyStore = AgnesKeyStore(redis) {
        // 用全局 sqlClient 加载所有 key（不依赖 ModuleCtx，因为 key 加载是 infra 级操作）
        val keys = sqlClient.createQuery(com.ifmix.core.api.entity.ai.AgnesKey::class) {
            select(table)
        }.execute()
        keys.map { k ->
            AgnesKeyStore.AgnesKeyDoc(
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

package com.ifmix.core.api.infra.ratelimit

import com.ifmix.core.api.modules.ai.service.ScanPromptProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 限流 bean 装配。RateLimiter 需 lambda-free 但含构造依赖，集中在此 @Bean。
 *
 * ScanPromptProperties 也在此登记（与 ScanQuotaConfig 同批）—— 提示词按 projectId 查表，
 * 见 com.ifmix.core.api.modules.ai.service.ScanPromptRegistry。
 */
@Configuration
@EnableConfigurationProperties(
    RateLimitConfig::class,
    ScanQuotaConfig::class,
    RateLimitProperties::class,
    ScanPromptProperties::class,
)
class RateLimitBeans {

    @Bean
    fun tierResolver(): TierResolver = FreeTierResolver()

    @Bean
    fun rateLimiter(
        redis: StringRedisTemplate,
        tierResolver: TierResolver,
        config: RateLimitConfig,
    ): RateLimiter = RateLimiter(redis, tierResolver, config)
}

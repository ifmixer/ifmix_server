package com.ifmix.api.core.infra.ratelimit

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 限流 bean 装配。RateLimiter 需 lambda-free 但含构造依赖，集中在此 @Bean。
 */
@Configuration
@EnableConfigurationProperties(RateLimitConfig::class)
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

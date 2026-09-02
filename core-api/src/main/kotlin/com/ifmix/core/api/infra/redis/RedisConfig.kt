package com.ifmix.core.api.infra.redis

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Configuration
class RedisCacheConfig {

    @Bean
    fun cacheAside(
        redisTemplate: RedisTemplate<String, String>,
        objectMapper: ObjectMapper,
    ): CacheAside = CacheAside(redisTemplate, objectMapper, defaultTtl = Duration.ofMinutes(5))
}

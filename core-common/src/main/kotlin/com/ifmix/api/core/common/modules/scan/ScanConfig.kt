package com.ifmix.api.core.common.modules.scan

import com.ifmix.api.core.common.infra.ratelimit.RateLimitConfig
import com.ifmix.api.core.common.infra.ratelimit.RateLimiter
import com.ifmix.api.core.common.infra.storage.ObjectStorage
import com.ifmix.api.core.common.infra.storage.S3ObjectStorage
import com.ifmix.api.core.common.infra.storage.StorageConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 古物模块 bean 装配。
 *
 * 将限流器、对象存储串联起来。
 * ScanRunner 由 SpringAiScanRunner(@Service @Primary) 直接提供。
 */
@Configuration
@EnableConfigurationProperties(RateLimitConfig::class)
class ScanConfig {

    @Bean
    @ConditionalOnMissingBean(com.ifmix.api.core.common.infra.ratelimit.TierResolver::class)
    fun tierResolver(): com.ifmix.api.core.common.infra.ratelimit.TierResolver {
        return com.ifmix.api.core.common.infra.ratelimit.FreeTierResolver()
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter::class)
    fun rateLimiter(
        redis: StringRedisTemplate,
        tierResolver: com.ifmix.api.core.common.infra.ratelimit.TierResolver,
        rateLimitConfig: RateLimitConfig,
    ): RateLimiter {
        return RateLimiter(redis, tierResolver, rateLimitConfig)
    }

    @Bean
    fun objectStorage(
        presigner: software.amazon.awssdk.services.s3.presigner.S3Presigner,
        s3Client: software.amazon.awssdk.services.s3.S3Client,
        config: StorageConfig,
    ): ObjectStorage {
        return S3ObjectStorage(presigner, s3Client, config)
    }
}

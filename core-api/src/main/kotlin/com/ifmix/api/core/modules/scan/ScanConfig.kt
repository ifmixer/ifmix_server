package com.ifmix.api.core.modules.scan

import com.ifmix.api.core.infra.ratelimit.RateLimitConfig
import com.ifmix.api.core.infra.ratelimit.RateLimiter
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.infra.storage.StorageConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 古物模块 bean 装配。
 *
 * 将限流器、对象存储、ScanRunner、AntiqueService 串联起来。
 */
@Configuration
@EnableConfigurationProperties(RateLimitConfig::class)
class ScanConfig {

    @Bean
    @ConditionalOnMissingBean(com.ifmix.api.core.infra.ratelimit.TierResolver::class)
    fun tierResolver(): com.ifmix.api.core.infra.ratelimit.TierResolver {
        return com.ifmix.api.core.infra.ratelimit.FreeTierResolver()
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter::class)
    fun rateLimiter(
        redis: StringRedisTemplate,
        tierResolver: com.ifmix.api.core.infra.ratelimit.TierResolver,
        rateLimitConfig: RateLimitConfig,
    ): RateLimiter {
        return RateLimiter(redis, tierResolver, rateLimitConfig)
    }

    /** 配置了 S3/R2（app.storage.type=s3）时用真实预签名存储。 */
    @Bean
    @ConditionalOnProperty(name = ["app.storage.type"], havingValue = "s3")
    fun objectStorage(presigner: software.amazon.awssdk.services.s3.presigner.S3Presigner, config: StorageConfig): ObjectStorage {
        return com.ifmix.api.core.infra.storage.S3ObjectStorage(presigner, config)
    }

    /** 未配置存储时的回落实现，保证本地/开发环境能启动（返回假 URL）。 */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage::class)
    fun noopObjectStorage(): ObjectStorage = com.ifmix.api.core.infra.storage.NoopObjectStorage()

    /** 回落 ScanRunner：未接入真实 AI 时用 stub，保证本地能启动。 */
    @Bean
    @ConditionalOnMissingBean(ScanRunner::class)
    fun stubScanRunner(): ScanRunner = StubScanRunner()
}

package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.ratelimit.RateLimitConfig
import com.ifmix.api.core.common.ratelimit.RateLimiter
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.common.storage.StorageConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 古物模块 bean 装配。
 *
 * 将限流器、对象存储、ScanRunner、AntiqueService 串联起来。
 */
@Configuration
class AntiqueConfig {

    @Bean
    @ConditionalOnMissingBean(RateLimiter::class)
    fun rateLimiter(
        redis: StringRedisTemplate,
        tierResolver: com.ifmix.api.core.common.ratelimit.TierResolver,
    ): RateLimiter {
        return RateLimiter(redis, tierResolver, RateLimitConfig())
    }

    /** 配置了 S3/R2（app.storage.type=s3）时用真实预签名存储。 */
    @Bean
    @ConditionalOnProperty(name = ["app.storage.type"], havingValue = "s3")
    fun objectStorage(presigner: software.amazon.awssdk.services.s3.presigner.S3Presigner, config: StorageConfig): ObjectStorage {
        return com.ifmix.api.core.common.storage.S3ObjectStorage(presigner, config)
    }

    /** 未配置存储时的回落实现，保证本地/开发环境能启动（返回假 URL）。声明在 s3 bean 之后，
     *  @ConditionalOnMissingBean 在同一配置类内按声明顺序求值：s3 已注册则跳过、否则用 Noop。 */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage::class)
    fun noopObjectStorage(): ObjectStorage = com.ifmix.api.core.common.storage.NoopObjectStorage()

    @Bean
    @ConditionalOnMissingBean(ScanRecordRepository::class)
    fun scanRecordRepository(clusterResolver: MongoClusterResolver): ScanRecordRepository {
        return ScanRecordRepository(clusterResolver.primary())
    }

    /** 回落 ScanRunner：未接入真实 AI（app.storage.type != s3）时用 stub，保证本地能启动。
     *  AiConfig 的 SpringAiScanRunner 为 @Primary，配了 s3 时优先生效。 */
    @Bean
    @ConditionalOnMissingBean(ScanRunner::class)
    fun stubScanRunner(): ScanRunner = StubScanRunner()

    @Bean
    @ConditionalOnMissingBean(AntiqueService::class)
    fun antiqueService(
        scanRunner: ScanRunner,
        objectStorage: ObjectStorage,
        rateLimiter: RateLimiter,
        mongo: MongoTemplate,
    ): AntiqueService {
        return AntiqueService(scanRunner, objectStorage, rateLimiter, mongo)
    }
}

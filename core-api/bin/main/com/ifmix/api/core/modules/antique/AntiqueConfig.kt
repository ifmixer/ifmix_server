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
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = arrayOf("app.storage.type"),
    havingValue = "s3",
    matchIfMissing = false,
)
class AntiqueConfig {

    @Bean
    @ConditionalOnMissingBean(RateLimiter::class)
    fun rateLimiter(
        redis: StringRedisTemplate,
        tierResolver: com.ifmix.api.core.common.ratelimit.TierResolver,
    ): RateLimiter {
        return RateLimiter(redis, tierResolver, RateLimitConfig())
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStorage::class)
    fun objectStorage(presigner: software.amazon.awssdk.services.s3.presigner.S3Presigner, config: StorageConfig): ObjectStorage {
        return com.ifmix.api.core.common.storage.S3ObjectStorage(presigner, config)
    }

    @Bean
    @ConditionalOnMissingBean(ScanRecordRepository::class)
    fun scanRecordRepository(clusterResolver: MongoClusterResolver): ScanRecordRepository {
        return ScanRecordRepository(clusterResolver.primary())
    }

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

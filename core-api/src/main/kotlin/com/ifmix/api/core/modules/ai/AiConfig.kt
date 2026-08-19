package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.ratelimit.RateLimitConfig
import com.ifmix.api.core.common.ratelimit.RateLimiter
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.common.storage.StorageConfig
import com.ifmix.api.core.modules.ai.entity.CollectionEntity
import com.ifmix.api.core.modules.ai.repo.CollectionItemRepository
import com.ifmix.api.core.modules.ai.repo.CollectionRepository
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import com.ifmix.api.core.modules.ai.ScanRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * AI 模块 bean 装配。
 *
 * 合并原来的 AntiqueConfig + CollectionConfig，将扫描（antique）和收藏（collection）
 * 统一装配在 ai 模块下。
 */
@Configuration
class AiConfig {

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

    /** 未配置存储时的回落实现，保证本地/开发环境能启动（返回假 URL）。 */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage::class)
    fun noopObjectStorage(): ObjectStorage = com.ifmix.api.core.common.storage.NoopObjectStorage()

    // ---- ScanRecord beans ----

    @Bean
    @ConditionalOnMissingBean(ScanRecordRepository::class)
    fun scanRecordRepository(clusterResolver: MongoClusterResolver, mongo: MongoTemplate): ScanRecordRepository {
        return ScanRecordRepository(CRUDOps(clusterResolver.primary(), com.ifmix.api.core.modules.ai.entity.ScanRecordEntity::class.java), mongo)
    }

    @Bean
    @ConditionalOnMissingBean(ScanRunner::class)
    fun stubScanRunner(): ScanRunner = StubScanRunner()

    // ---- Collection beans ----

    @Bean
    @ConditionalOnMissingBean(CollectionRepository::class)
    fun collectionRepository(clusterResolver: MongoClusterResolver): CollectionRepository {
        return CollectionRepository(clusterResolver.primary())
    }

    @Bean
    @ConditionalOnMissingBean(CollectionItemRepository::class)
    fun collectionItemRepository(clusterResolver: MongoClusterResolver): CollectionItemRepository {
        return CollectionItemRepository(clusterResolver.primary())
    }

    @Bean
    fun collectionCrudRepository(clusterResolver: MongoClusterResolver): CRUDOps<CollectionEntity> {
        return CRUDOps(clusterResolver.primary(), CollectionEntity::class.java)
    }

    @Bean
    @ConditionalOnMissingBean(AiFacade::class)
    fun aiFacade(
        scanRunner: ScanRunner,
        objectStorage: ObjectStorage,
        rateLimiter: RateLimiter,
        mongo: MongoTemplate,
        scanRecordRepo: ScanRecordRepository,
        collectionCrudOps: CRUDOps<CollectionEntity>,
        collectionRepo: CollectionRepository,
        collectionItemRepo: CollectionItemRepository,
        collectionMembership: org.springframework.beans.factory.ObjectProvider<CollectionMembership>,
    ): AiFacade {
        return AiFacade(
            scanRunner = scanRunner,
            objectStorage = objectStorage,
            rateLimiter = rateLimiter,
            mongo = mongo,
            scanRecordRepo = scanRecordRepo,
            collectionCrudOps = collectionCrudOps,
            collectionRepo = collectionRepo,
            collectionItemRepo = collectionItemRepo,
            collectionMembership = collectionMembership,
        )
    }
}

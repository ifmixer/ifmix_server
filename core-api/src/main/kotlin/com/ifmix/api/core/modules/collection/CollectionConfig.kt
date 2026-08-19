package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.modules.antique.AntiqueService
import com.ifmix.api.core.modules.antique.CollectionMembership
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.ifmix.api.core.modules.collection.entity.CollectionEntity

/**
 * 收藏模块 bean 装配。
 *
 * 将 CollectionRepository、CollectionItemRepository、CRUDService、CollectionService、
 * CollectionMembershipImpl 串联起来。
 */
@Configuration
class CollectionConfig {

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

    // 注意：勿加 @ConditionalOnMissingBean(CRUDRepository/CRUDService)——它们按擦除后的原始类型匹配，
    // 会与 todo/feedback 等模块的同类 bean 冲突导致本模块 bean 被跳过。各模块各自定义自己的泛型 bean，
    // Spring 按泛型参数（CollectionEntity）区分注入。
    @Bean
    fun collectionCrudRepository(
        clusterResolver: MongoClusterResolver,
    ): CRUDRepository<CollectionEntity> {
        return CRUDRepository(clusterResolver.primary(), CollectionEntity::class.java)
    }

    @Bean
    fun collectionCrudService(repo: CRUDRepository<CollectionEntity>): CRUDService<CollectionEntity> {
        return CRUDService(repo)
    }

    @Bean
    @ConditionalOnMissingBean(CollectionService::class)
    fun collectionService(
        collectionCrud: CRUDService<CollectionEntity>,
        itemRepo: CollectionItemRepository,
        collectionRepo: CollectionRepository,
        antiqueService: AntiqueService,
    ): CollectionService {
        return CollectionService(collectionCrud, itemRepo, collectionRepo, antiqueService)
    }

    @Bean
    @ConditionalOnMissingBean(CollectionMembership::class)
    fun collectionMembership(
        mongo: org.springframework.data.mongodb.core.MongoTemplate,
        collectionRepo: CollectionRepository,
    ): CollectionMembership {
        return CollectionMembershipImpl(mongo, collectionRepo)
    }
}

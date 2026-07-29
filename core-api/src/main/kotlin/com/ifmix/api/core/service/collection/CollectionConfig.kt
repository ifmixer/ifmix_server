package com.ifmix.api.core.service.collection

import com.ifmix.api.core.repository.collection.CollectionRepository
import com.ifmix.api.core.repository.collection.CollectionItemRepository
import com.ifmix.api.core.service.antique.CollectionMembership
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 收藏模块 bean 装配（Jimmer + PostgreSQL）。
 * Repository 由 @Component 自动注册，CollectionService 由 @Service 自动注册。
 */
@Configuration
class CollectionConfig {

    @Bean
    @ConditionalOnMissingBean(CollectionMembership::class)
    fun collectionMembership(
        itemRepo: CollectionItemRepository,
        collectionRepo: CollectionRepository,
    ): CollectionMembership {
        return CollectionMembershipImpl(itemRepo, collectionRepo)
    }
}

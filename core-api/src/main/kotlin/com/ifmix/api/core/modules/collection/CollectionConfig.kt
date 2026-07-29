package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.repository.collection.CollectionRepository
import com.ifmix.api.core.common.jimmer.repository.collection.CollectionItemRepository
import com.ifmix.api.core.modules.antique.CollectionMembership
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 收藏模块 bean 装配（Jimmer + PostgreSQL）。
 * Repository 由 @Component 自动注册，这里只配置 Service。
 */
@Configuration
class CollectionConfig {

    @Bean
    @ConditionalOnMissingBean(CollectionService::class)
    fun collectionService(
        collectionRepo: CollectionRepository,
        itemRepo: CollectionItemRepository,
    ): CollectionService {
        return CollectionService(collectionRepo, itemRepo)
    }

    @Bean
    @ConditionalOnMissingBean(CollectionMembership::class)
    fun collectionMembership(): CollectionMembership {
        return CollectionMembershipImplStub()
    }
}

private class CollectionMembershipImplStub : CollectionMembership {
    override fun isCollected(ctx: RequestContext, scanRecordId: String): Boolean = false
}

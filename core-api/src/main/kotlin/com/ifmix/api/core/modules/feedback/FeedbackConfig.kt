package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.service.CRUDService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.ifmix.api.core.modules.feedback.entity.FeedbackEntity

/** feedback 模块 bean 装配。feedback 集合不软删（FeedbackEntity 未实现 SoftDeletable）。 */
@Configuration
class FeedbackConfig {

    @Bean
    fun feedbackRepository(clusterResolver: MongoClusterResolver): CRUDOps<FeedbackEntity> =
        CRUDOps(clusterResolver.primary(), FeedbackEntity::class.java)

    @Bean
    fun feedbackCrudService(feedbackRepository: CRUDOps<FeedbackEntity>): CRUDService<FeedbackEntity> =
        CRUDService(feedbackRepository)

    @Bean
    fun feedbackService(feedbackCrudService: CRUDService<FeedbackEntity>): FeedbackService =
        FeedbackService(feedbackCrudService)
}

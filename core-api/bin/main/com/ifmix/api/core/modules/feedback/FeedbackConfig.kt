package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.service.CRUDService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** feedback 模块 bean 装配。feedback 集合不软删（FeedbackDocument 未实现 SoftDeletable）。 */
@Configuration
class FeedbackConfig {

    @Bean
    fun feedbackRepository(clusterResolver: MongoClusterResolver): CRUDRepository<FeedbackDocument> =
        CRUDRepository(clusterResolver.primary(), FeedbackDocument::class.java)

    @Bean
    fun feedbackCrudService(feedbackRepository: CRUDRepository<FeedbackDocument>): CRUDService<FeedbackDocument> =
        CRUDService(feedbackRepository)

    @Bean
    fun feedbackService(feedbackCrudService: CRUDService<FeedbackDocument>): FeedbackService =
        FeedbackService(feedbackCrudService)
}

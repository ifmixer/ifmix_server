package com.ifmix.api.core.service.iap

import com.ifmix.api.core.service.appconfig.AppConfigRepo
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.annotation.Qualifier

/**
 * IAP 模块 bean 装配。
 */
@Configuration
class IapConfig {

    @Bean
    @ConditionalOnMissingBean(name = arrayOf("appleVerifier"))
    fun appleVerifier(): PurchaseVerifier = StubPurchaseVerifier()

    @Bean
    @ConditionalOnMissingBean(name = arrayOf("googleVerifier"))
    fun googleVerifier(): PurchaseVerifier = StubPurchaseVerifier()

    @Bean
    fun iapService(
        @Qualifier("appleVerifier") appleVerifier: PurchaseVerifier,
        @Qualifier("googleVerifier") googleVerifier: PurchaseVerifier,
        subscriptionRepo: com.ifmix.api.core.repository.iap.SubscriptionRepository,
    ): IapService {
        return IapService(appleVerifier, googleVerifier, subscriptionRepo)
    }

    @Bean
    @ConditionalOnMissingBean(name = arrayOf("appleDecoder"))
    fun appleDecoder(): NotificationDecoder = StubNotificationDecoder()

    @Bean
    @ConditionalOnMissingBean(name = arrayOf("googleDecoder"))
    fun googleDecoder(): NotificationDecoder = StubNotificationDecoder()
}

package com.ifmix.api.core.modules.iap

import com.ifmix.api.core.modules.appconfig.repo.AppConfigRepo
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.annotation.Qualifier
import com.ifmix.api.core.modules.iap.repo.SubscriptionRepo

/**
 * IAP 模块 bean 装配。
 *
 * 将 verifier、decoder、subscriptionRepo、appConfigRepo 串联为 IapService，
 * 并注册 createIapTierResolver 作为 TierResolver（@Primary），
 * 使 antique 等下游模块自动获得基于 IAP 订阅的档位判定。
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
        subscriptionRepo: SubscriptionRepo,
        appConfigRepo: AppConfigRepo,
    ): IapService {
        return IapService(appleVerifier, googleVerifier, subscriptionRepo, appConfigRepo)
    }

    @Bean
    @ConditionalOnMissingBean(name = arrayOf("appleDecoder"))
    fun appleDecoder(): NotificationDecoder = StubNotificationDecoder()

    @Bean
    @ConditionalOnMissingBean(name = arrayOf("googleDecoder"))
    fun googleDecoder(): NotificationDecoder = StubNotificationDecoder()

    /**
     * 基于 IAP 订阅的 TierResolver——@Primary 覆盖 antique 自带的 FreeTierResolver。
     * 有活跃订阅的用户自动晋升为 PRO 档。
     */
    @Bean
    @Primary
    fun tierResolver(subscriptionRepo: SubscriptionRepo): com.ifmix.api.core.common.ratelimit.TierResolver {
        return createIapTierResolver(subscriptionRepo)
    }
}

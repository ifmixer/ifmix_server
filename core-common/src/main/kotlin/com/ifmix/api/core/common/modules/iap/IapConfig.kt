package com.ifmix.api.core.common.modules.iap

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
    @ConditionalOnMissingBean(name = arrayOf("appleDecoder"))
    fun appleDecoder(): NotificationDecoder = StubNotificationDecoder()

    @Bean
    @ConditionalOnMissingBean(name = arrayOf("googleDecoder"))
    fun googleDecoder(): NotificationDecoder = StubNotificationDecoder()
}

package com.ifmix.api.core.modules.payment

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * IAP 模块 bean 装配。
 *
 * 当前使用 Stub 实现（不调用真实商店 API）。
 * 生产环境替换为真实的 Apple/Google verifier 和 decoder。
 */
@Configuration
class IapConfig {

    @Bean("appleVerifier")
    fun appleVerifier(): PurchaseVerifier = StubPurchaseVerifier()

    @Bean("googleVerifier")
    fun googleVerifier(): PurchaseVerifier = StubPurchaseVerifier()

    @Bean("appleDecoder")
    fun appleDecoder(): NotificationDecoder = StubNotificationDecoder()

    @Bean("googleDecoder")
    fun googleDecoder(): NotificationDecoder = StubNotificationDecoder()
}

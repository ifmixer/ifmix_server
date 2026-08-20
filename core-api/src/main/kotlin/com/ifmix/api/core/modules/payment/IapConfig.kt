package com.ifmix.api.core.modules.payment

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * IAP 模块 bean 装配。
 *
 * 当前使用 Stub 实现（不调用真实商店 API）。
 * 生产环境替换为 ApplePurchaseVerifier / GooglePurchaseVerifier。
 */
@Configuration
class IapConfig {

    @Bean("appleVerifier")
    fun appleVerifier(): PurchaseVerifier = StubPurchaseVerifier()

    @Bean("googleVerifier")
    fun googleVerifier(): PurchaseVerifier = StubPurchaseVerifier()
}

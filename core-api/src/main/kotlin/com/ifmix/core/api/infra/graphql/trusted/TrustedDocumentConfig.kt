package com.ifmix.core.api.infra.graphql.trusted

import graphql.execution.preparsed.PreparsedDocumentProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 注册 Trusted Document 的 [PreparsedDocumentProvider] bean。
 *
 * DGS 的 `preparsedDocumentProvider` 默认 bean 标注了 @ConditionalOnMissingBean，
 * 因此这里提供自定义 bean 后 DGS 会退让并采用本实现；DGS 的 sourceBuilderCustomizer
 * 会把它注入 GraphQlSource（官方扩展点，无需自写 GraphQlSourceBuilderCustomizer）。
 *
 * trusted-documents 机制恒开（x-api-name 恒走 allowlist）。allow-raw-query：
 *  - false（默认，uat/prod）→ 未传 x-api-name 一律拒绝，强制走 persisted query。
 *  - true（local）→ 未传 x-api-name 时回退 body raw query，供 GraphiQL/API 探索。
 */
@Configuration
class TrustedDocumentConfig {

    @Bean
    fun trustedDocumentProvider(
        store: PersistedQueryStore,
        @Value("\${graphql.trusted-documents.allow-raw-query:false}") allowRawQuery: Boolean,
    ): PreparsedDocumentProvider = TrustedDocumentProvider(store, allowRawQuery)
}

package com.ifmix.core.api.infra.graphql

import com.ifmix.core.api.infra.http.HeaderValidationInterceptor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * GraphQL 端点拦截器配置。
 *
 * DGS 默认注册 /graphql，通过 application.yml 的 spring.graphql.path=/customer/graphql 重映射路径。
 *
 * 拦截器策略：
 * - HeaderValidationInterceptor：应用于 /customer/graphql，校验 x-app-id 等必填头。
 * - AuthInterceptor：不应用于 /customer/graphql。GraphQL 通过 OperationContextProvider
 *   从原始 HttpServletRequest 中提取 userId，与 REST 路径的拦截器方案解耦。
 */
@Configuration
@ConditionalOnProperty(name = ["app.graphql.enabled"], havingValue = "true", matchIfMissing = true)
class GraphQLEndpointConfig(
    private val headerValidationInterceptor: HeaderValidationInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(headerValidationInterceptor)
            .addPathPatterns("/customer/graphql")
    }
}

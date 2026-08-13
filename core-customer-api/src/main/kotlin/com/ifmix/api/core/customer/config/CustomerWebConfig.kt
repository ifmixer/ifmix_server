package com.ifmix.api.core.customer.config

import com.ifmix.api.core.common.infra.auth.AuthInterceptor
import com.ifmix.api.core.common.infra.http.HeaderValidationInterceptor
import com.ifmix.api.core.common.infra.http.OperationContextArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Customer API 的 Web 配置：注册请求头校验拦截器与 OperationContext 参数解析器。
 * 仅适用于 core-api（Customer 端）。
 */
@Configuration
class CustomerWebConfig(
    private val headerValidationInterceptor: HeaderValidationInterceptor,
    private val authInterceptor: AuthInterceptor,
    private val operationContextArgumentResolver: OperationContextArgumentResolver,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(headerValidationInterceptor)
            .addPathPatterns("/customer/**")
            .excludePathPatterns(
                "/core/api-docs/**",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
            )

        // JWT 认证拦截器：仅对需要认证的端点生效
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/customer/query/core/**", "/customer/mutation/core/**")
            .excludePathPatterns(
                "/.well-known/**",
            )
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(operationContextArgumentResolver)
    }
}

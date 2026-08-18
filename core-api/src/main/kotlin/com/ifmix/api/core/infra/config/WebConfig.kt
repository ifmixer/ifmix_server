package com.ifmix.api.core.infra.config

import com.ifmix.api.core.infra.auth.AuthInterceptor
import com.ifmix.api.core.infra.http.HeaderValidationInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 注册请求头校验拦截器（仅 customer/app-admin）与 OperationContext 参数解析器。 */
@Configuration
class WebConfig(
    private val headerValidationInterceptor: HeaderValidationInterceptor,
    private val authInterceptor: AuthInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(headerValidationInterceptor)
            .addPathPatterns("/customer/**", "/app/**")
            // openapi/swagger 端点无需 appId（本就不在 /customer、/app 下，这里显式排除以自文档化、防未来路径变更）
            .excludePathPatterns(
                "/core/api-docs/**",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
            )

        // JWT 认证拦截器：仅对需要认证的端点生效
        // /customer/mutation/core/auth/* 不需要（登录/刷新/交换是公开接口）
        // /customer/*/core/* 需要（业务 API）
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/customer/query/core/**", "/customer/mutation/core/**")
            .excludePathPatterns(
                "/.well-known/**",
            )

        // GraphQL 端点也需要相同拦截器
        registry.addInterceptor(headerValidationInterceptor)
            .addPathPatterns("/customer/graphql")
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/customer/graphql")
    }
}

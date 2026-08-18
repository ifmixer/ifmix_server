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
            .addPathPatterns("/customer/**")
            .excludePathPatterns(
                "/apidocs/**",
            )

        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/customer/**")
            .excludePathPatterns(
                "/apidocs/**",
                "/.well-known/**",
            )
    }
}

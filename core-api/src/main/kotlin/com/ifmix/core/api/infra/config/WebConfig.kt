package com.ifmix.core.api.infra.config

import com.ifmix.core.api.infra.auth.AuthInterceptor
import com.ifmix.core.api.infra.http.HeaderValidationInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 注册请求头校验拦截器（仅 customer/app-admin）与 OperationContext 参数解析器。 */
@Configuration
class WebConfig(
    private val headerValidationInterceptor: HeaderValidationInterceptor,
    private val authInterceptor: AuthInterceptor,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("*")
            .allowedHeaders("*")
            .allowCredentials(true)
    }

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

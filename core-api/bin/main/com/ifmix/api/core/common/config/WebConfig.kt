package com.ifmix.api.core.common.config

import com.ifmix.api.core.common.http.HeaderValidationInterceptor
import com.ifmix.api.core.common.http.RequestContextArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 注册请求头校验拦截器（仅 customer/app-admin）与 RequestContext 参数解析器。 */
@Configuration
class WebConfig(
    private val headerValidationInterceptor: HeaderValidationInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(headerValidationInterceptor)
            .addPathPatterns("/customer/**", "/app/**")
            // openapi/swagger 端点无需 appId（本就不在 /customer、/app 下，这里显式排除以自文档化、防未来路径变更）
            .excludePathPatterns(
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
            )
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(RequestContextArgumentResolver())
    }
}

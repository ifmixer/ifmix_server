package com.ifmix.core.api.infra.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 配置。
 *
 * 不再注册 token/header 解析拦截器——请求解析与校验已下沉到 GraphQL 层
 * [com.ifmix.core.api.infra.graphql.OperationContextProvider.fromDfe]（经 RequestParser），
 * 失败即抛 ApiError → 统一 GraphQL 错误格式。
 */
@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("*")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}

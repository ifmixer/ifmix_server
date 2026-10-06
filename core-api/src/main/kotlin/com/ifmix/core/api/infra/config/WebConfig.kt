package com.ifmix.core.api.infra.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 配置。
 *
 * 不注册 token/header 解析拦截器——RPC 请求的解析与校验在 [com.ifmix.core.api.infra.http.ActionContextFactory.fromRpc]
 * 内完成（失败即抛 ApiError → GlobalExceptionHandler 统一信封格式）。
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

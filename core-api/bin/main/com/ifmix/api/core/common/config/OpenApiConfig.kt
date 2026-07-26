package com.ifmix.api.core.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import com.ifmix.api.core.common.http.RequestContext
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 每 BFF 一个 OpenAPI 分组 + 以 x-app-id 为 header 的统一 apiKey 安全方案。 */
@Configuration
class OpenApiConfig {

    init {
        // ctx 由 RequestContextArgumentResolver 注入，不是真正的请求参数——让 springdoc 忽略它，
        // 否则每个接口都会多出一个必填的 ctx query 参数并污染生成的客户端。
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(RequestContext::class.java)
    }

    @Bean
    fun customerApi(): GroupedOpenApi =
        GroupedOpenApi.builder().group("customer").pathsToMatch("/customer/**").build()

    @Bean
    fun appAdminApi(): GroupedOpenApi =
        GroupedOpenApi.builder().group("app").pathsToMatch("/app/**").build()

    @Bean
    fun platformAdminApi(): GroupedOpenApi =
        GroupedOpenApi.builder().group("platform").pathsToMatch("/platform/**").build()

    @Bean
    fun coreOpenApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("ifmix core-api").version("v1"))
            .components(
                Components().addSecuritySchemes(
                    APP_ID_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                       .`in`(SecurityScheme.In.HEADER)
                        .name("x-app-id"),
                ),
            )
            .addSecurityItem(SecurityRequirement().addList(APP_ID_SCHEME))

    companion object {
        private const val APP_ID_SCHEME = "appId"
    }
}

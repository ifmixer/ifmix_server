package com.ifmix.api.core.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 每 BFF 一个 OpenAPI 分组 + 以 x-app-id 为 header 的统一 apiKey 安全方案。 */
@Configuration
class OpenApiConfig {

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

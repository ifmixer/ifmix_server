package com.ifmix.api.core.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import com.ifmix.api.core.infra.http.OperationContext
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 配置。
 *
 * Header 声明策略：
 * - 8 个 x-* header 声明在 components.parameters，每个 operation 通过 $ref 引用
 * - 全部 required: false（前端 middleware 自动注入，TS 类型不强制手传）
 * - 服务端 HeaderValidationInterceptor 仍强制校验 x-app-id 必填
 */
@Configuration
class OpenApiConfig {

    init {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(OperationContext::class.java)
        SpringDocUtils.getConfig().replaceWithClass(java.time.Instant::class.java, Long::class.javaObjectType)
    }

    @Bean
    fun envelopeSchemaCustomizer(): EnvelopeSchemaCustomizer = EnvelopeSchemaCustomizer()

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
    fun coreOpenApi(): OpenAPI {
        val components = Components()
            .addSecuritySchemes(
                "bearerAuth",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT access token（Authorization: Bearer {token}）"),
            )
            .addSecuritySchemes("xAppId", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-app-id").description("租户 App ID（uuid，必填）"))
            .addSecuritySchemes("xInstallId", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-install-id").description("安装 ID（uuid）"))
            .addSecuritySchemes("xLang", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-lang").description("语言，如 en"))
            .addSecuritySchemes("xCurrency", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-currency").description("货币，如 USD"))
            .addSecuritySchemes("xCountry", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-country").description("国家，如 US"))
            .addSecuritySchemes("xNativeVersion", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-native-version").description("原生版本"))
            .addSecuritySchemes("xJsVersion", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-js-version").description("JS 版本"))
            .addSecuritySchemes("xClientPlatform", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("x-client-platform").description("客户端平台：android | ios | web"))

        return OpenAPI()
            .info(Info().title("ifmix customer BFF").version("1.0.0"))
            .servers(listOf(Server().url("http://localhost:3001").description("Local development")))
            .components(components)
            // 全局 security：Swagger UI Authorize 弹窗显示这些
            .addSecurityItem(
                SecurityRequirement()
                    .addList("bearerAuth")
                    .addList("xAppId")
                    .addList("xInstallId")
                    .addList("xLang")
                    .addList("xCurrency")
                    .addList("xCountry")
                    .addList("xNativeVersion")
                    .addList("xJsVersion")
                    .addList("xClientPlatform"),
            )
    }

    /**
     * GlobalOpenApiCustomizer:
     * 1. 免鉴权接口 security: []
     * 2. operationId 加模块前缀
     */
    @Bean
    fun headerAndSecurityCustomizer(): GlobalOpenApiCustomizer = object : GlobalOpenApiCustomizer {

        private val publicPaths = setOf(
            "/auth/google",
            "/auth/apple",
            "/auth/wechat",
            "/auth/exchange",
            "/auth/refresh",
            "/auth/anonymous",
        )

        override fun customise(openApi: OpenAPI) {
            val paths = openApi.paths ?: return
            for ((path, pathItem) in paths) {
                for (operation in pathItem.readOperations()) {
                    // 免鉴权
                    if (publicPaths.any { path.endsWith(it) }) {
                        operation.security = emptyList()
                    }

                    // operationId 前缀
                    val id = operation.operationId
                    if (id != null && !id.contains("_")) {
                        val module = path.removePrefix("/").split("/").getOrNull(3)
                        if (module != null) {
                            operation.operationId = "$module${id.replaceFirstChar { it.uppercase() }}"
                        }
                    }
                }
            }
        }
    }

}

package com.ifmix.api.core.infra.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import com.ifmix.api.core.infra.http.RequestContext
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
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(RequestContext::class.java)
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

        // 注册公共 header parameters（给 codegen 用）
        for ((name, param) in COMMON_HEADERS) {
            components.addParameters(name, param)
        }

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
     * 1. 注入 header parameters ($ref)
     * 2. 免鉴权接口 security: []
     * 3. operationId 加模块前缀
     */
    @Bean
    fun headerAndSecurityCustomizer(): GlobalOpenApiCustomizer = object : GlobalOpenApiCustomizer {

        private val publicPaths = setOf(
            "/auth/google",
            "/auth/apple",
            "/auth/wechat",
            "/auth/exchange",
            "/auth/refresh",
        )

        override fun customise(openApi: OpenAPI) {
            val paths = openApi.paths ?: return
            for ((path, pathItem) in paths) {
                for (operation in pathItem.readOperations()) {
                    // 注入 header $ref
                    val params = operation.parameters
                        ?: mutableListOf<Parameter>().also { operation.parameters = it }
                    val existing = params.mapNotNull { it.name ?: it.`$ref`?.substringAfterLast('/') }.toSet()
                    for (name in COMMON_HEADERS.keys) {
                        if (name !in existing) {
                            params.add(Parameter().`$ref`("#/components/parameters/$name"))
                        }
                    }

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

    companion object {
        /** 公共 header 参数。全部 required: false（middleware 自动注入）。 */
        val COMMON_HEADERS: LinkedHashMap<String, Parameter> = linkedMapOf(
            "x-app-id" to Parameter().`in`("header").name("x-app-id")
                .description("租户 App ID（uuid）。服务端必填，由 middleware 自动注入。")
                .required(false).schema(StringSchema().apply { format = "uuid" }),
            "x-install-id" to Parameter().`in`("header").name("x-install-id")
                .description("设备安装标识（uuid）")
                .required(false).schema(StringSchema().apply { format = "uuid" }),
            "x-lang" to Parameter().`in`("header").name("x-lang")
                .description("语言代码，如 en / zh-Hans。影响 AI 结果语言。")
                .required(false).schema(StringSchema()),
            "x-currency" to Parameter().`in`("header").name("x-currency")
                .description("货币代码，如 USD / CNY")
                .required(false).schema(StringSchema()),
            "x-country" to Parameter().`in`("header").name("x-country")
                .description("国家代码，如 US / CN")
                .required(false).schema(StringSchema()),
            "x-native-version" to Parameter().`in`("header").name("x-native-version")
                .description("原生应用版本号，如 1.2.0")
                .required(false).schema(StringSchema()),
            "x-js-version" to Parameter().`in`("header").name("x-js-version")
                .description("JS bundle 版本号，如 1.2.3")
                .required(false).schema(StringSchema()),
            "x-client-platform" to Parameter().`in`("header").name("x-client-platform")
                .description("客户端平台")
                .required(false).schema(StringSchema().apply { enum = listOf("android", "ios", "web") }),
        )
    }
}

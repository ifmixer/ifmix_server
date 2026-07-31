package com.ifmix.api.core.infra.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
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
 * OpenAPI 配置：
 * - 每 BFF 一个分组
 * - securitySchemes 保留给 Swagger UI "Authorize" 弹窗
 * - operation-level header parameters 给 codegen 用（openapi-typescript 只认 parameters）
 * - bearerAuth scheme 用于标注需要 JWT 的接口
 */
@Configuration
class OpenApiConfig {

    init {
        // ctx 由 RequestContextArgumentResolver 注入，不是真正的请求参数——让 springdoc 忽略它
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(RequestContext::class.java)
        // Instant 对外序列化为 epoch 毫秒，OpenAPI 里按 int64 呈现
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
    fun coreOpenApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("ifmix customer BFF").version("1.0.0"))
            .servers(
                listOf(
                    Server().url("http://localhost:3001").description("Local development"),
                ),
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        "bearerAuth",
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("JWT access token（Authorization: Bearer {token}）"),
                    ),
            )
            // 全局默认需要 Bearer token（免鉴权接口由 customizer 覆盖为 security: []）
            .addSecurityItem(SecurityRequirement().addList("bearerAuth"))

    /**
     * P0-2: 给免鉴权接口覆盖 security: []。
     * P0-5: 给 operationId 加模块前缀。
     *
     * 注意：header parameters 不再注入到 operation 上。
     * openapi-typescript + openapi-fetch 的 middleware 模式下，
     * 如果声明了 header parameters（即使 required=false），
     * 生成的类型会强制要求 `params` 对象，与 middleware 自动注入冲突。
     * headers 信息通过 securitySchemes 提供文档，实际值由 middleware 自动设置。
     */
    @Bean
    fun headerAndSecurityCustomizer(): GlobalOpenApiCustomizer = object : GlobalOpenApiCustomizer {

        /** 免鉴权路径后缀（不需要 Bearer token） */
        private val publicPaths = setOf(
            "/auth/google",
            "/auth/apple",
            "/auth/exchange",
            "/auth/refresh",
        )

        override fun customise(openApi: OpenAPI) {
            val paths = openApi.paths ?: return
            for ((path, pathItem) in paths) {
                for (operation in pathItem.readOperations()) {
                    // --- 免鉴权标注 ---
                    if (publicPaths.any { path.endsWith(it) }) {
                        operation.security = emptyList() // security: []
                    }

                    // --- operationId 加模块前缀 ---
                    val currentId = operation.operationId
                    if (currentId != null && !currentId.contains("_")) {
                        val module = extractModule(path)
                        if (module != null) {
                            operation.operationId = "$module${currentId.replaceFirstChar { it.uppercase() }}"
                        }
                    }
                }
            }
        }

        /** 从路径提取模块名：/customer/core/{query|mutation}/{module}/{action} */
        private fun extractModule(path: String): String? {
            val segments = path.removePrefix("/").split("/")
            // customer / core / query|mutation / module / action
            return if (segments.size >= 5) segments[3] else null
        }
    }
}

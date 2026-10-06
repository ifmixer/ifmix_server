package com.ifmix.core.api.infra.http

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * dev 态 OpenAPI 增强（仅 local profile；proposal「凭证与 meta 分离」§dev 态输入适配）：
 * 给 RPC 全量操作声明两个 header 型 securityScheme，Swagger UI 右上角 Authorize 一次全局生效
 * （配合 application.yml `persist-authorization: true`，重载页面不丢）：
 *
 * - `DevAuthorization` → `Authorization` header：凭证（纯 token，可带 Bearer 前缀，适配层会剥）。
 *   一次全局填好，body.meta.accessToken 留空即可。
 * - `DevReqMeta` → `x-req-meta` header：[RequestMeta] JSON（projectId/locale 等，不含凭证），
 *   作 body.meta 的字段级底座。
 *
 * 两者在同一 [SecurityRequirement] 内（AND）：Authorize 后每个请求自动带两个 header，
 * 由 [DevRpcHeaderAdapter] 在 `ActionContextFactory.fromRpc` 合并。prod 不加载本配置——
 * api-docs/swagger-ui 默认关闭，且 prod 不认这两个 header。
 */
@Configuration
@Profile("local")
class DevRpcOpenApiConfig {

    companion object {
        const val DEV_AUTH_SCHEME = "DevAuthorization"
        const val DEV_META_SCHEME = "DevReqMeta"
    }

    @Bean
    fun devRpcOpenApi(): OpenAPI = OpenAPI()
        .components(
            Components()
                .addSecuritySchemes(
                    DEV_AUTH_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("Authorization")
                        .description(
                            "dev 专用凭证槽位：access token（裸 token 或 Bearer 前缀均可，服务端自动剥前缀）。" +
                                "body.meta.accessToken 优先于本 header；prod 不认 Authorization header。",
                        ),
                )
                .addSecuritySchemes(
                    DEV_META_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name(RequestHeaders.DEV_REQ_META)
                        .description(
                            "dev 专用 meta 底座：RequestMeta JSON（如 {\"projectId\":\"ifmix-demo\",\"locale\":\"zh-CN\"}），" +
                                "body.meta 字段级优先。不含凭证（accessToken 字段被忽略）。",
                        ),
                ),
        )
        .security(
            listOf(
                SecurityRequirement().addList(DEV_AUTH_SCHEME).addList(DEV_META_SCHEME),
            ),
        )
}

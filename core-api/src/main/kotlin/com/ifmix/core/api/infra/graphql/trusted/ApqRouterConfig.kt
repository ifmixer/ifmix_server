package com.ifmix.core.api.infra.graphql.trusted

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.server.webmvc.GraphQlHttpHandler
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse

/**
 * APQ（persisted query）HTTP 入口路由。
 *
 * 把 APQ 请求（如 `/customer/core/apq/q_ai_findMyScanById`）映射到 Spring GraphQL 自动装配的
 * 同一个 [GraphQlHttpHandler]。因此 APQ 与 GQL（`spring.graphql.path`）共用同一套
 * WebGraphQlInterceptor 链与执行引擎，仅入口路径不同：
 *  - GQL `/customer/core/gql`：raw query，供 GraphiQL / 本地探索（Spring Boot 默认注册）。
 *  - APQ `/customer/core/apq/{apqName}`：apqName 在 path 末段，[ApqNamePathInterceptor] 解析后
 *    交 [TrustedDocumentProvider] 查 allowlist。前置层（CF/nginx）可按具体路径分流。
 *
 * 用单段通配（apq-path 后跟一个路径段）让 apqName 作为一个路径段透传；解析见 [ApqNamePathInterceptor]。
 */
@Configuration
class ApqRouterConfig {

    @Bean
    fun apqRouterFunction(
        graphQlHttpHandler: GraphQlHttpHandler,
        @Value("\${graphql.trusted-documents.apq-path:/customer/core/apq}") apqPath: String,
    ): RouterFunction<ServerResponse> {
        val pattern = apqPath.trimEnd('/') + "/" + WILDCARD_SEGMENT
        return RouterFunctions.route()
            .POST(pattern, graphQlHttpHandler::handleRequest)
            .build()
    }

    companion object {
        /** Spring 单段路径通配符（匹配一个路径段，即 apqName）。 */
        private const val WILDCARD_SEGMENT = "*"
    }
}

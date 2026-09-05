package com.ifmix.core.api.infra.graphql.trusted

import org.springframework.core.annotation.Order
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * 读取 `x-api-name` header 并放入 GraphQLContext，供 [TrustedDocumentProvider] 使用。
 *
 * x-api-name = 前端 persisted query 标识（如 `q_ai_findMyScanById`）。
 * 不改 body、不解析 body；仅把 header 值透传进 GraphQLContext。
 */
@Component
@Order(0)
class ApiNameHeaderInterceptor : WebGraphQlInterceptor {

    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        val apiName = request.headers.getFirst(HEADER_API_NAME)
        if (!apiName.isNullOrBlank()) {
            request.configureExecutionInput { _, builder ->
                builder.graphQLContext(mapOf(CTX_API_NAME to apiName)).build()
            }
        }
        return chain.next(request)
    }

    companion object {
        const val HEADER_API_NAME = "x-api-name"
        const val CTX_API_NAME = "trusted.apiName"
    }
}

package com.ifmix.core.api.infra.graphql.trusted

import org.springframework.core.annotation.Order
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * 从 APQ 请求 URL path 解析 apiName 并放入 GraphQLContext，供 [TrustedDocumentProvider] 使用。
 *
 * APQ 入口：`POST /customer/core/apq/{apiName}`（如 `.../apq/q_ai_findMyScanById`）。
 * apiName = 前端 persisted query 标识，取 [APQ_PATH_SEGMENT] 之后的**整段末尾**。
 *
 * 走 GQL raw query 入口（`/customer/core/gql`）的请求 path 里没有 `/apq/` 段 → apiName 为空
 * → [TrustedDocumentProvider] 天然走 raw-query 分支。
 *
 * 不改 body、不解析 body；仅把 path 里的 apiName 透传进 GraphQLContext。
 */
@Component
@Order(0)
class ApiNamePathInterceptor : WebGraphQlInterceptor {

    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        val apiName = extractApiName(request.uri.path ?: "")
        if (!apiName.isNullOrBlank()) {
            request.configureExecutionInput { _, builder ->
                builder.graphQLContext(mapOf(CTX_API_NAME to apiName)).build()
            }
        }
        return chain.next(request)
    }

    /**
     * 取 `/apq/` 之后的末段作为 apiName。
     * 无 `/apq/` 段（如走 `/gql`）或末段为空 → 返回 null。
     */
    private fun extractApiName(path: String): String? {
        val idx = path.indexOf(APQ_PATH_SEGMENT)
        if (idx < 0) return null
        return path.substring(idx + APQ_PATH_SEGMENT.length)
            .trim('/')
            .substringBefore('/')      // 只取紧跟 /apq/ 的第一段
            .takeIf { it.isNotBlank() }
    }

    companion object {
        /** APQ path 中的定位段；apiName 紧跟其后。 */
        const val APQ_PATH_SEGMENT = "/apq/"
        const val CTX_API_NAME = "trusted.apiName"
    }
}

package com.ifmix.core.api.infra.graphql.trusted

import org.springframework.core.annotation.Order
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * 从 GReq（persisted query）请求 URL path 解析 reqName 并放入 GraphQLContext，供 [TrustedDocumentProvider] 使用。
 *
 * GReq 入口：`POST /customer/core/greq/{reqName}`（如 `.../greq/q_ai_scan_getMyById`）。
 * reqName = 前端 persisted query 标识，取 [GREQ_PATH_SEGMENT] 之后的**整段末尾**。
 *
 * 走 GQL raw query 入口（`/customer/core/gql`）的请求 path 里没有 `/greq/` 段 → reqName 为空
 * → [TrustedDocumentProvider] 天然走 raw-query 分支。
 *
 * 不改 body、不解析 body；仅把 path 里的 reqName 透传进 GraphQLContext。
 */
@Component
@Order(0)
class ReqNamePathInterceptor : WebGraphQlInterceptor {

    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        val reqName = extractReqName(request.uri.path ?: "")
        if (!reqName.isNullOrBlank()) {
            request.configureExecutionInput { _, builder ->
                builder.graphQLContext(mapOf(CTX_REQ_NAME to reqName)).build()
            }
        }
        return chain.next(request)
    }

    /**
     * 取 `/greq/` 之后的末段作为 reqName。
     * 无 `/greq/` 段（如走 `/gql`）或末段为空 → 返回 null。
     */
    private fun extractReqName(path: String): String? {
        val idx = path.indexOf(GREQ_PATH_SEGMENT)
        if (idx < 0) return null
        return path.substring(idx + GREQ_PATH_SEGMENT.length)
            .trim('/')
            .substringBefore('/')      // 只取紧跟 /greq/ 的第一段
            .takeIf { it.isNotBlank() }
    }

    companion object {
        /** GReq path 中的定位段；reqName 紧跟其后。 */
        const val GREQ_PATH_SEGMENT = "/greq/"
        const val CTX_REQ_NAME = "trusted.reqName"
    }
}

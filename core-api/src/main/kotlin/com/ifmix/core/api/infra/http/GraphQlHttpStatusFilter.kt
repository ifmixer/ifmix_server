package com.ifmix.core.api.infra.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import tools.jackson.databind.ObjectMapper

/**
 * 把 GraphQL response 的 `errors[0].extensions.code` 前 3 位映射为 HTTP 状态码。
 *
 * 背景：GraphQL over HTTP（legacy JSON）恒返回 200，业务错误码放在 body 里。为让
 * CF/nginx/客户端能直接按 HTTP status 判断，这里读 response body 的第一个 error 的
 * `extensions.code`（如 `"401000"`），取前 3 位（`401`）设为 HTTP status。
 *
 * - 无 errors（或无法解析出 code）→ 不改动，保持 handler 原状态（成功即 200）。
 * - 仅作用于 GraphQL/GReq 端点（[GRAPHQL_PATH_MARKERS]），其余请求直接放行不缓冲。
 *
 * @Order 高优先级：包在业务之外，确保改后的 status 对客户端生效；
 * [RequestLoggingFilter] 再包在本 filter 外层（HIGHEST_PRECEDENCE + 5），日志读到的是改写后的最终 status。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class GraphQlHttpStatusFilter(private val mapper: ObjectMapper) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(GraphQlHttpStatusFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return GRAPHQL_PATH_MARKERS.none { path.contains(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val wrapped = ContentCachingResponseWrapper(response)
        try {
            filterChain.doFilter(request, wrapped)
        } finally {
            statusFromBody(wrapped.contentAsByteArray)?.let { wrapped.status = it }
            // 必须 copyBodyToResponse，否则客户端收不到响应体。
            wrapped.copyBodyToResponse()
        }
    }

    /**
     * 从 GraphQL response body 解析 `errors[0].extensions.code` 前 3 位为 HTTP status。
     * 无 errors / 无 code / code 非法 → null（不改状态）。
     */
    private fun statusFromBody(body: ByteArray): Int? {
        if (body.isEmpty()) return null
        val code = try {
            val root = mapper.readTree(body)
            root.get("errors")?.takeIf { it.isArray && !it.isEmpty }
                ?.get(0)?.get("extensions")?.get("code")?.asString()
        } catch (e: Exception) {
            log.debug("GraphQlHttpStatusFilter: cannot parse response body. error={}", e.message)
            return null
        }
        if (code.isNullOrBlank() || code.length < 3) return null
        return code.take(3).toIntOrNull()?.takeIf { it in 100..599 }
    }

    companion object {
        /** 命中即视为 GraphQL/GReq 端点（避免对其他 REST/静态资源缓冲 body）。 */
        private val GRAPHQL_PATH_MARKERS = listOf("/gql", "/greq/")
    }
}

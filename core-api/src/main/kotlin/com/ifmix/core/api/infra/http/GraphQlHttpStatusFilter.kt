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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * GraphQL response 适配：
 * 1. 顶层注入 `code` / `msg`——客户端统一契约是 `{code, msg, data}`（Envelope），GraphQL 的
 *    `errors` 数组照留不删，但顶层必须有 code/msg（取自第一个 error：`extensions.code` / `message`）。
 *    非业务 error（GraphQL 校验/语法错误等，无 extensions.code）按 classification/errorType
 *    推导兜底 code，避免顶层缺字段。
 * 2. 把 `errors[0].extensions.code` 前 3 位映射为 HTTP 状态码。
 *
 * 背景：GraphQL over HTTP（legacy JSON）恒返回 200，业务错误码放在 body 里。为让
 * CF/nginx/客户端能直接按 HTTP status 判断，这里读 response body 的第一个 error 的
 * `extensions.code`（如 `"401000"`），取前 3 位（`401`）设为 HTTP status。兜底推导的 code
 * 不参与 status 映射（无 extensions.code 时保持 handler 原状态）。
 *
 * - 无 errors（成功响应）→ body 原样透传，不注入、不缓冲重写。
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
            rewriteBody(wrapped)
            // 必须 copyBodyToResponse，否则客户端收不到响应体。
            wrapped.copyBodyToResponse()
        }
    }

    /**
     * 有 errors 时：注入顶层 `code`/`msg`，并按 extensions.code 前 3 位设 HTTP status。
     * body 无法解析 / 无 errors → 原样放行。
     */
    private fun rewriteBody(wrapped: ContentCachingResponseWrapper) {
        val body = wrapped.contentAsByteArray
        if (body.isEmpty()) return
        val root = try {
            mapper.readTree(body)
        } catch (e: Exception) {
            log.debug("GraphQlHttpStatusFilter: cannot parse response body. error={}", e.message)
            return
        }
        val firstError = root.get("errors")?.takeIf { it.isArray && !it.isEmpty }?.get(0) ?: return

        val code = firstError.get("extensions")?.get("code")?.asString()
        code?.take(3)?.toIntOrNull()?.takeIf { it in 100..599 }?.let { wrapped.status = it }

        val obj = root as? ObjectNode ?: return
        obj.put("code", code ?: fallbackCode(firstError))
        firstError.get("message")?.asString()?.let { obj.put("msg", it) }
        try {
            val rewritten = mapper.writeValueAsBytes(root)
            wrapped.resetBuffer()
            wrapped.outputStream.write(rewritten)
        } catch (e: Exception) {
            // 注入失败按原 body 返回（status 映射已生效），不影响错误送达。
            wrapped.resetBuffer()
            wrapped.outputStream.write(body)
            log.warn("GraphQlHttpStatusFilter: inject code/msg failed. error={}", e.message)
        }
    }

    /**
     * 无 extensions.code 的 error（GraphQL 校验/语法/超时等框架级错误）的兜底 code：
     * 从 errorType/classification 推导客户端错误类；推不出 → 500000。
     */
    private fun fallbackCode(firstError: JsonNode): String {
        val kind = firstError.get("extensions")?.let { ext ->
            ext.get("errorType")?.asString() ?: ext.get("classification")?.asString()
        } ?: return "500000"
        return when {
            kind.contains("Unauthorized", ignoreCase = true) -> "401000"
            kind.contains("Forbidden", ignoreCase = true) -> "403000"
            kind.contains("NotFound", ignoreCase = true) -> "404000"
            kind.contains("Validation", ignoreCase = true) ||
                kind.contains("Syntax", ignoreCase = true) ||
                kind.contains("BadRequest", ignoreCase = true) ||
                kind.contains("OperationNotSupported", ignoreCase = true) -> "400000"
            else -> "500000"
        }
    }

    companion object {
        /** 命中即视为 GraphQL/GReq 端点（避免对其他 REST/静态资源缓冲 body）。 */
        private val GRAPHQL_PATH_MARKERS = listOf("/gql", "/greq/")
    }
}

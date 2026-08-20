package com.ifmix.api.core.infra.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import com.ifmix.api.core.infra.jimmer.OperationContextHolder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * HTTP 请求/响应日志 Filter。
 * - 正常请求：DEBUG 级别打印请求方法、路径、请求体、响应状态和耗时。
 * - 错误响应（4xx/5xx）：WARN 级别额外打印响应体，方便排查问题。
 */
@Component
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        // 跳过静态资源、actuator、swagger 等
        return path.startsWith("/actuator") ||
                path.startsWith("/core/api-docs") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/swagger-ui")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val wrappedRequest = ContentCachingRequestWrapper(request, 10_000) // 缓存最多 10KB 请求体
        val wrappedResponse = ContentCachingResponseWrapper(response)

        val start = System.currentTimeMillis()

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse)
        } finally {
            val duration = System.currentTimeMillis() - start
            val status = wrappedResponse.status
            val method = wrappedRequest.method
            val uri = wrappedRequest.requestURI
            val query = wrappedRequest.queryString?.let { "?$it" } ?: ""

            val requestBody = getBody(wrappedRequest.contentAsByteArray, wrappedRequest.characterEncoding)
            val responseBody = getBody(wrappedResponse.contentAsByteArray, wrappedResponse.characterEncoding)

            if (status >= 400) {
                // 错误响应：WARN 级别，打印完整请求体和响应体
                val appId = wrappedRequest.getHeader("x-app-id") ?: "<none>"
                val installId = wrappedRequest.getHeader("x-install-id") ?: "<none>"
                log.warn(
                    "▶ {} {}{} | status={} | {}ms | appId={} installId={}\n  ├─ req: {}\n  └─ res: {}",
                    method, uri, query, status, duration, appId, installId,
                    requestBody.truncate(2000),
                    responseBody.truncate(2000),
                )
            } else if (log.isDebugEnabled) {
                // 正常响应：DEBUG 级别
                log.debug(
                    "▶ {} {}{} | status={} | {}ms | req: {} | res: {}",
                    method, uri, query, status, duration,
                    requestBody.truncate(500),
                    responseBody.truncate(500),
                )
            }

            // 必须 copyBodyToResponse，否则客户端收不到响应体
            wrappedResponse.copyBodyToResponse()
            // 清理 ThreadLocal，防止虚拟线程池中的上下文泄漏
            OperationContextHolder.clear()
        }
    }

    private fun getBody(content: ByteArray, encoding: String?): String {
        if (content.isEmpty()) return "<empty>"
        return String(content, charset(encoding ?: "UTF-8"))
    }

    private fun String.truncate(max: Int): String =
        if (length <= max) this else substring(0, max) + "...(truncated)"
}

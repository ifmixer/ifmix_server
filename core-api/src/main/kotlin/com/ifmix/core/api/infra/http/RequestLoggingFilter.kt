package com.ifmix.core.api.infra.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import com.ifmix.core.api.infra.auth.RequestParser
import com.ifmix.core.api.infra.jimmer.OperationContextHolder
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
class RequestLoggingFilter(private val parser: RequestParser) : OncePerRequestFilter() {

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

            // 跳过 IntrospectionQuery 的日志输出（IDE/工具高频探测，无业务价值）
            if (requestBody.contains("IntrospectionQuery") || requestBody.contains("__schema")) {
                wrappedResponse.copyBodyToResponse()
                OperationContextHolder.clear()
                return
            }

            val responseBody = getBody(wrappedResponse.contentAsByteArray, wrappedResponse.characterEncoding)
            val headers = importantHeaders(wrappedRequest)

            if (status >= 400) {
                // 错误响应：WARN 级别，打印完整请求体和响应体
                log.warn(
                    "▶ {} {}{} | status={} | {}ms | {}\n  ├─ req: {}\n  └─ res: {}",
                    method, uri, query, status, duration, headers,
                    requestBody.truncate(2000),
                    responseBody.truncate(2000),
                )
            } else if (log.isDebugEnabled) {
                // 正常响应：DEBUG 级别
                log.debug(
                    "▶ {} {}{} | status={} | {}ms | {} | req: {} | res: {}",
                    method, uri, query, status, duration, headers,
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

    /**
     * 汇总重要请求头 + clientIp + userId 用于排查。只输出存在的值，避免噪音；
     * Authorization 脱敏（只标存在与 scheme，绝不打 token 明文）。
     * userId 取自 AuthInterceptor 解析后存入 request attribute 的 RequestContext.actorId。
     */
    private fun importantHeaders(request: HttpServletRequest): String {
        val parts = mutableListOf<String>()
        for (name in LOGGED_HEADERS) {
            request.getHeader(name)?.takeIf { it.isNotBlank() }?.let { parts.add("$name=$it") }
        }
        request.getHeader("Authorization")?.takeIf { it.isNotBlank() }?.let {
            val scheme = it.substringBefore(' ', it).take(16)
            parts.add("Authorization=$scheme ***")
        }
        // clientIp / userId 取自 RequestParser（token 幂等缓存，与 fromDfe 共享同一 request 缓存）
        parts.add("clientIp=${parser.parseClientIp(request)}")
        parser.peekActorId(request)?.let { parts.add("userId=$it") }
        return parts.joinToString(" ")
    }

    private fun String.truncate(max: Int): String =
        if (length <= max) this else substring(0, max) + "...(truncated)"

    companion object {
        /** 排查用的重要请求头（不含 Authorization，后者单独脱敏处理）。 */
        private val LOGGED_HEADERS = listOf(
            RequestHeaders.APP_ID,
            RequestHeaders.INSTALL_ID,
            "x-api-name",
            RequestHeaders.CLIENT_PLATFORM,
            RequestHeaders.LOCALE,
            RequestHeaders.CURRENCY,
            RequestHeaders.COUNTRY,
            RequestHeaders.NATIVE_VERSION,
            RequestHeaders.BUNDLE_VERSION,
        )
    }
}

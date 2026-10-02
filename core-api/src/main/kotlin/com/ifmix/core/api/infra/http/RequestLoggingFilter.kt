package com.ifmix.core.api.infra.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import com.ifmix.core.api.infra.auth.RequestParser
import com.ifmix.core.api.infra.jimmer.ActionContextHolder
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
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
// 最外层：必须包住 GraphQlHttpStatusFilter，日志里的 httpStatus 才是它改写后、客户端实际收到的值；
// 也让 reqId/MDC 尽早建立，内层 filter 的日志同样带 rid。
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RequestLoggingFilter(private val parser: RequestParser) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        // 跳过根路径/健康检查（探活高频、无排查价值）、actuator、swagger 等
        return path == "/" || path == "/core/health" ||
                path.startsWith("/actuator") ||
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
        // 先写响应头（body 提交前），日志里的 rid 与之相同
        response.setHeader(RequestHeaders.REQ_ID, LogContext.start(wrappedRequest))

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
                ActionContextHolder.clear()
                LogContext.clear()
                return
            }

            // ctx 在 data fetcher 线程构造，filter 线程的 MDC 为空——从 request 取回再绑，汇总行才带上下文字段。
            // 取到 ctx 时 header 信息已在 MDC 里，不再重复打印；没构造过 ctx（进入 GraphQL 前就失败、非 GraphQL 路由）才回退打 headers。
            val hasCtx = LogContext.bindFrom(wrappedRequest)

            // 错误响应 WARN（body 截 2000），正常 DEBUG（截 500）。字段走 SLF4J key-value，JSON 日志里各成顶层字段（duration 为数值 ms）。
            val error = status >= 400
            val builder = when {
                error -> log.atWarn()
                log.isDebugEnabled -> log.atDebug()
                else -> null
            }
            if (builder != null) {
                val max = if (error) 2000 else 500
                builder
                    .addKeyValue("method", method)
                    .addKeyValue("path", uri + query)
                    .addKeyValue("httpStatus", status)
                    .addKeyValue("duration", duration)
                    .apply { if (!hasCtx) addKeyValue("headers", importantHeaders(wrappedRequest)) }
                    .addKeyValue("req", requestBody.truncate(max))
                    .addKeyValue("res", getBody(wrappedResponse.contentAsByteArray, wrappedResponse.characterEncoding).truncate(max))
                    .log("http.request.end")
            }

            // 必须 copyBodyToResponse，否则客户端收不到响应体
            wrappedResponse.copyBodyToResponse()
            // 清理 ThreadLocal，防止虚拟线程池中的上下文泄漏
            ActionContextHolder.clear()
            LogContext.clear()
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
            RequestHeaders.PROJECT_ID,
            RequestHeaders.INSTALL_ID,
            RequestHeaders.CLIENT_PLATFORM,
            RequestHeaders.CF_BOT_SCORE,
            RequestHeaders.LOCALE,
            RequestHeaders.CURRENCY,
            RequestHeaders.COUNTRY,
            RequestHeaders.APP_VERSION,
            RequestHeaders.OTA_VERSION,
        )
    }
}

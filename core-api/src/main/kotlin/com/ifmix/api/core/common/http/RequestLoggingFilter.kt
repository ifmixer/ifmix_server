package com.ifmix.api.core.common.http

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.IOException

/**
 * 请求日志过滤器：打印 method、path、请求体摘要、status、耗时。
 * 错误响应（4xx/5xx）额外打印响应体内容，便于排查问题。
 *
 * 跳过 actuator、api-docs、swagger-ui 等框架路径，避免日志噪音。
 */
@Component
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheLimit = 2048  // 最大缓存 2KB 请求体

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        if (shouldSkip(path)) {
            filterChain.doFilter(request, response)
            return
        }

        val startNs = System.nanoTime()
        // 必须传入 cacheLimit 参数，否则构造函数不兼容
        val cachedRequest = ContentCachingRequestWrapper(request, cacheLimit)
        val cachedResponse = ContentCachingResponseWrapper(response)

        try {
            filterChain.doFilter(cachedRequest, cachedResponse)
        } finally {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            val status = response.status
            val method = request.method
            val requestBody = String(cachedRequest.contentAsByteArray)
            val responseBody = String(cachedResponse.contentAsByteArray)
            val reqSnippet = if (requestBody.length > 200) requestBody.take(200) + "..." else requestBody
            val resSnippet = if (responseBody.length > 200) responseBody.take(200) + "..." else responseBody

            if (status >= 400) {
                log.warn(
                    "[{}] {} {} | body={} | resp={}",
                    status, elapsedMs, method, reqSnippet.ifEmpty { "-" }, resSnippet.ifEmpty { "-" },
                )
            } else {
                log.debug(
                    "[{}] {} {} | body={} | resp={}",
                    status, elapsedMs, method, reqSnippet.ifEmpty { "-" }, resSnippet.ifEmpty { "-" },
                )
            }
        }
    }

    private fun shouldSkip(path: String): Boolean {
        return path.startsWith("/actuator") ||
            path.startsWith("/v3/api-docs") ||
            path.startsWith("/swagger-ui")
    }

    /**
     * 将过滤器注册为 Spring Bean，确保优先于其他过滤器执行。
     */
    @Configuration
    class FilterRegistrar {
        @Bean
        fun requestLoggingFilterBean(filter: RequestLoggingFilter): FilterRegistrationBean<*> {
            return FilterRegistrationBean(filter).apply {
                order = Int.MIN_VALUE + 100  // 尽早执行
            }
        }
    }
}

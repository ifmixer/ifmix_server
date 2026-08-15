package com.ifmix.api.core.graphql.common.trusted

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Trusted Documents filter（OncePerRequestFilter）。
 *
 * 请求 body 中如果有 extensions.persistedQuery.sha256Hash：
 * - 查 allowlist，命中 → 将 query 字段注入到 body → 放行
 * - 未命中 + enabled=true → 返回 403
 * - 未命中 + enabled=false → 放行原始 query（开发模式）
 *
 * 没有 persistedQuery extension 且 enabled=true → 拒绝（要求必须通过 persisted query 调用）
 */
@Component
@Order(1)
class TrustedDocumentFilter(
    private val store: PersistedQueryStore,
    @Value("\${graphql.trusted-documents.enabled:false}")
    private val enabled: Boolean,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val uri = request.requestURI
        if (!uri.endsWith("/graphql")) {
            filterChain.doFilter(request, response)
            return
        }

        val bodyBytes = request.inputStream.readBytes()
        val bodyStr = String(bodyBytes, Charsets.UTF_8)
        val hash = extractPersistedQueryHash(bodyStr)

        if (hash != null) {
            val bff = if (uri.startsWith("/admin/")) "admin" else "customer"
            val entry = store.get(hash, bff)

            if (entry != null) {
                val newBody = injectQuery(bodyStr, entry.query)
                request.setAttribute("trusted.operation.name", entry.name)
                filterChain.doFilter(CachedBodyRequest(request, newBody.toByteArray(Charsets.UTF_8)), response)
                return
            } else if (enabled) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Query not in allowlist")
                return
            }
        } else if (enabled) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Only persisted queries allowed")
            return
        }

        // 开发模式或无 hash 且 disabled → 放行原始 body
        filterChain.doFilter(CachedBodyRequest(request, bodyBytes), response)
    }

    /** 简单正则提取 sha256Hash，避免引入额外 JSON 解析开销。 */
    private fun extractPersistedQueryHash(body: String): String? {
        val regex = """"sha256Hash"\s*:\s*"([a-fA-F0-9]+)"""".toRegex()
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun injectQuery(body: String, query: String): String {
        val escaped = query.replace("\"", "\\\"").replace("\n", "\\n")
        val queryRegex = """"query"\s*:\s*"[^"]*"""".toRegex()
        return if (queryRegex.containsMatchIn(body)) {
            queryRegex.replace(body, """"query":"$escaped"""")
        } else {
            body.replaceFirst("{", """{"query":"$escaped",""")
        }
    }
}

/** 包装 HttpServletRequest 以支持重复读取已修改的 body。 */
class CachedBodyRequest(
    request: HttpServletRequest,
    private val cachedBody: ByteArray,
) : HttpServletRequestWrapper(request) {

    override fun getInputStream() = ByteArrayInputStream(cachedBody).let { bytes ->
        object : jakarta.servlet.ServletInputStream() {
            override fun read(): Int = bytes.read()
            override fun isFinished() = bytes.available() == 0
            override fun isReady() = true
            override fun setReadListener(listener: jakarta.servlet.ReadListener?) {}
        }
    }

    override fun getReader() = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
}

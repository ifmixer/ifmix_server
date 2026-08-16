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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Trusted Documents filter（OncePerRequestFilter）。
 *
 * 请求支持两种模式：
 * 1. **x-op-id 头**（优先）：客户端发 x-op-id header，从 store 按 name 查找 query 并注入 body
 * 2. **persisted query hash**（向后兼容）：body 中 extensions.persistedQuery.sha256Hash，
 *    从 store 按 hash 查找 query 并注入 body
 *
 * 没有持久化查询且 enabled=true → 返回 403
 */
@Component
@Order(1)
class TrustedDocumentFilter(
    private val store: PersistedQueryStore,
    @Value("\${graphql.trusted-documents.enabled:false}")
    private val enabled: Boolean,
) : OncePerRequestFilter() {

    private val mapper = ObjectMapper()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val uri = request.requestURI
        if (!uri.startsWith("/customer/graphql") && !uri.startsWith("/admin/graphql")) {
            filterChain.doFilter(request, response)
            return
        }

        val bff = if (uri.startsWith("/admin/")) "admin" else "customer"
        val bodyBytes = request.inputStream.readBytes()
        val bodyStr = String(bodyBytes, Charsets.UTF_8)
        val opId = request.getHeader("x-op-id")

        if (opId != null) {
            // 新路径：x-op-id header
            val entry = store.getByName(opId, bff)
            if (entry == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown operation: $opId")
                return
            }
            val newBody = buildBody(entry, bodyStr)
            request.setAttribute("trusted.operation.name", entry.name)
            filterChain.doFilter(CachedBodyRequest(request, newBody.toByteArray(Charsets.UTF_8)), response)
            return
        }

        // 原有路径：从 body 提取 sha256Hash
        val hash = extractPersistedQueryHash(bodyStr)
        if (hash != null) {
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

    /** 将 x-op-id 请求的 body 包装为包含注入 query 的完整 GraphQL request。 */
    private fun buildBody(entry: PersistedQueryEntry, originalBody: String): String {
        val variables = extractVariables(originalBody)
        val escaped = entry.query.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"query":"$escaped","operationName":"${entry.name}","variables":$variables}"""
    }

    private fun extractVariables(body: String): String {
        if (body.isBlank() || body == "{}") return "{}"
        return try {
            val node: JsonNode = mapper.readTree(body)
            node.get("variables")?.toString() ?: "{}"
        } catch (_: Exception) {
            "{}"
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

    override fun getContentLength() = cachedBody.size

    override fun getContentLengthLong() = cachedBody.size.toLong()
}

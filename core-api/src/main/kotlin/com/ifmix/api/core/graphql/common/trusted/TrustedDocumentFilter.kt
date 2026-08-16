package com.ifmix.api.core.graphql.common.trusted

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * 限制 customer GraphQL 入口只接受 persisted query（通过 x-op-id header 或 query hash）。
 * 当 graphql.trusted-documents.enabled=true 时启用。
 *
 * admin 入口（/admin/graphql）跳过此检查，保持开放 introspection。
 */
@Component
@ConditionalOnProperty(name = ["graphql.trusted-documents.enabled"], havingValue = "true")
class TrustedDocumentFilter(
    private val persistedQueryStore: PersistedQueryStore,
    @Value("\${graphql.trusted-documents.max-query-size:65536}") private val maxQuerySize: Int,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // admin 入口跳过检查
        if (request.requestURI.startsWith("/admin/graphql")) {
            filterChain.doFilter(request, response)
            return
        }

        // 检查 x-op-id header
        val opId = request.getHeader("x-op-id")
        if (opId != null) {
            val query = persistedQueryStore.get(opId)
            if (query == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown persisted query: $opId")
                return
            }
            // 将解析出的 query 注入请求 attribute，供 DGS DgsQueryExecutor 使用
            request.setAttribute("dgs.persisted.query", query)
            filterChain.doFilter(request, response)
            return
        }

        // 读取请求体并检查是否为已注册的 query hash
        val body = request.inputStream.bufferedReader().readText()
        if (body.length > maxQuerySize) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Query exceeds maximum size")
            return
        }

        // 检查是否是 persisted query 注册请求（包含 persistedQuery 字段）
        if ("persistedQuery" in body) {
            filterChain.doFilter(request, response)
            return
        }

        // 计算 SHA-256 hash 并检查
        val hash = sha256(body)
        if (persistedQueryStore.contains(hash)) {
            request.setAttribute("dgs.persisted.query", body)
            filterChain.doFilter(request, response)
            return
        }

        log.warn("Rejected non-persisted query from {} (size: {})", request.remoteAddr, body.length)
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Query not in persisted query store. Provide x-op-id header or register the query first.")
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

package com.ifmix.api.core.graphql.router

import com.netflix.graphql.dgs.DgsQueryExecutor
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest

/**
 * GraphQL 双入口路由控制器。
 *
 * - POST /customer/graphql — persisted query only（通过 TrustedDocumentFilter 保护）
 * - POST /admin/graphql   — 开放 introspection
 *
 * 两个入口均通过 DgsQueryExecutor 透传到内部 DGS 端点处理，
 * 结果通过 ExecutionResult.toSpecification() 序列化为标准 GraphQL JSON 响应。
 */
@RestController
@RequestMapping("/graphql")
class GraphQLRouterController(
    private val dgsQueryExecutor: DgsQueryExecutor,
) {

    @PostMapping("/customer")
    fun customerGraphql(
        @RequestBody body: Map<String, Any>,
        request: HttpServletRequest,
    ): Map<String, Any> {
        return executeQuery(body, request)
    }

    @PostMapping("/admin")
    fun adminGraphql(
        @RequestBody body: Map<String, Any>,
        request: HttpServletRequest,
    ): Map<String, Any> {
        return executeQuery(body, request)
    }

    private fun executeQuery(body: Map<String, Any>, request: HttpServletRequest): Map<String, Any> {
        val query = body["query"] as? String
            ?: throw IllegalArgumentException("Missing 'query' field in request body")
        val variables = body["variables"] as? Map<String, Any> ?: emptyMap()
        val operationName = body["operationName"] as? String
        val extensions = body["extensions"] as? Map<String, Any>

        // 如果请求中有 persisted query 缓存，优先使用
        val resolvedQuery = request.getAttribute("dgs.persisted.query") as? String ?: query

        val result = dgsQueryExecutor.execute(
            resolvedQuery,
            variables,
            extensions ?: emptyMap(),
            null,
            operationName,
            null,
        )

        @Suppress("UNCHECKED_CAST")
        return result.toSpecification() as Map<String, Any>
    }
}

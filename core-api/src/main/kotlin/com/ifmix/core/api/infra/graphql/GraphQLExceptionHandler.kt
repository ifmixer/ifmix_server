package com.ifmix.core.api.infra.graphql

import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * GraphQL 异常解析器。
 *
 * ApiError → 带 code 的 GraphQL error（extensions.code = "401000" 等）。
 * 其他异常 → 返回 null，走 Spring GraphQL 默认处理。
 *
 * @Order(1) 确保优先于 DGS 内置的默认 exception resolver。
 */
@Component
@Order(1)
class GraphQLExceptionHandler : DataFetcherExceptionResolverAdapter() {

    private val log = LoggerFactory.getLogger(GraphQLExceptionHandler::class.java)

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? {
        val path = env.executionStepInfo.path
        // 异常可能被 DGS/future 包装（CompletionException 等），沿 cause 链找 ApiError。
        val apiError = generateSequence(ex) { it.cause }.filterIsInstance<ApiError>().firstOrNull()
        if (apiError != null) {
            // 集中分级记日志：5xx 服务端故障 error(带 stack)；限流/第三方验证失败 warn；其余客户端错误 debug。
            val code = apiError.errorCode
            when {
                code.status.is5xxServerError ->
                    log.error("GraphQL ApiError [{}] {} at {}", code.externalCode, apiError.message, path, apiError)
                code == ErrorCode.RATE_LIMITED || code == ErrorCode.QUOTA_EXCEEDED ||
                    code == ErrorCode.AUTH_PROVIDER_FAILED ->
                    log.warn("GraphQL ApiError [{}] {} at {}", code.externalCode, apiError.message, path)
                else ->
                    log.debug("GraphQL ApiError [{}] {} at {}", code.externalCode, apiError.message, path)
            }
            return GraphqlErrorBuilder.newError(env)
                .message(apiError.message ?: code.name)
                .errorType(code.toGraphQLErrorType())
                .extensions(mapOf(
                    "code" to code.externalCode,
                    "errorName" to code.name,
                ))
                .build()
        }
        // 非 ApiError：未预期的程序异常（NPE/DB/Redis 等）。记 error 带 stack 便于排查，
        // 再返回 null 走 Spring GraphQL 默认处理（对客户端仍是 INTERNAL_ERROR）。
        log.error("GraphQL unhandled exception at {}", path, ex)
        return null
    }
}

/** 将 ErrorCode 的 HTTP 语义映射到 Spring GraphQL ErrorType。 */
private fun com.ifmix.core.api.infra.http.ErrorCode.toGraphQLErrorType(): ErrorType = when {
    externalCode.startsWith("400") -> ErrorType.BAD_REQUEST
    externalCode.startsWith("401") -> ErrorType.UNAUTHORIZED
    externalCode.startsWith("403") -> ErrorType.FORBIDDEN
    externalCode.startsWith("404") -> ErrorType.NOT_FOUND
    else -> ErrorType.INTERNAL_ERROR
}

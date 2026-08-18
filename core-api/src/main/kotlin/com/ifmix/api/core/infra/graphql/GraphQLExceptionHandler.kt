package com.ifmix.api.core.infra.graphql

import com.ifmix.api.core.infra.http.ApiError
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
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

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? {
        if (ex is ApiError) {
            return GraphqlErrorBuilder.newError(env)
                .message(ex.message ?: ex.errorCode.name)
                .errorType(ex.errorCode.toGraphQLErrorType())
                .extensions(mapOf(
                    "code" to ex.errorCode.externalCode,
                    "errorName" to ex.errorCode.name,
                ))
                .build()
        }
        // 其他异常返回 null → 走默认处理
        return null
    }
}

/** 将 ErrorCode 的 HTTP 语义映射到 Spring GraphQL ErrorType。 */
private fun com.ifmix.api.core.infra.http.ErrorCode.toGraphQLErrorType(): ErrorType = when {
    externalCode.startsWith("400") -> ErrorType.BAD_REQUEST
    externalCode.startsWith("401") -> ErrorType.UNAUTHORIZED
    externalCode.startsWith("403") -> ErrorType.FORBIDDEN
    externalCode.startsWith("404") -> ErrorType.NOT_FOUND
    else -> ErrorType.INTERNAL_ERROR
}

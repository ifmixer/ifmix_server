package com.ifmix.api.core.infra.graphql

import com.ifmix.api.core.infra.http.ApiError
import graphql.ErrorType
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

/**
 * GraphQL 异常解析器。
 *
 * ApiError → 带 code 的 GraphQL error（extensions.code = "401002" 等）。
 * 其他异常 → 默认 INTERNAL_ERROR。
 */
@Component
class GraphQLExceptionHandler : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? {
        if (ex is ApiError) {
            return GraphqlErrorBuilder.newError(env)
                .message(ex.message ?: ex.errorCode.name)
                .errorType(ErrorType.DataFetchingException)
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

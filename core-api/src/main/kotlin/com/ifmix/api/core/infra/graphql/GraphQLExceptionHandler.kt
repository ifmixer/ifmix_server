package com.ifmix.api.core.infra.graphql

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.netflix.graphql.dgs.exceptions.DefaultDataFetcherExceptionHandler
import graphql.ErrorType
import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * GraphQL 数据提取器异常处理器。
 *
 * ApiError（业务异常）被转换为带 errorCode 扩展的 GraphQL error；
 * 其他异常交由 DefaultDataFetcherExceptionHandler 默认处理。
 *
 * 注意：graphql-java 25 的 ErrorType 枚举只有 6 个值（InvalidSyntax / ValidationError /
 * DataFetchingException / NullValueInNonNullableField / OperationNotSupported / ExecutionAborted），
 * 不再包含 PERMISSION_DENIED / NOT_FOUND / UNAVAILABLE / BAD_REQUEST。
 * 业务语义通过 extensions["errorCode"] 传递，ErrorType 统一用 DataFetchingException。
 */
@Component
class GraphQLExceptionHandler : DefaultDataFetcherExceptionHandler() {

    override fun handleException(
        params: DataFetcherExceptionHandlerParameters,
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val ex = params.exception
        return if (ex is ApiError) {
            val error = GraphQLError.newError()
                .message(ex.message ?: ex.errorCode.name)
                .errorType(ErrorType.DataFetchingException)
                .path(params.path)
                .extensions(mapOf("errorCode" to ex.errorCode.externalCode))
                .build()
            CompletableFuture.completedFuture(
                DataFetcherExceptionHandlerResult.newResult().error(error).build()
            )
        } else {
            super.handleException(params)
        }
    }
}

package com.ifmix.core.api.infra.graphql

import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.GENERIC_SERVER_ERROR_MESSAGE
import com.ifmix.core.api.infra.http.HeaderDump
import com.ifmix.core.api.infra.http.clientMessage
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * GraphQL 异常解析器。
 *
 * ApiError → 带 code 的 GraphQL error（extensions.code = "401000" 等）。
 * 其他异常 → INTERNAL_ERROR（code=500000）。
 * 线上（app.expose-errors=false）5xx 与未预期异常只返回通用文案，不透出异常细节。
 *
 * @Order(1) 确保优先于 DGS 内置的默认 exception resolver。
 */
@Component
@Order(1)
class GraphQLExceptionHandler(
    @param:Value("\${app.expose-errors:false}") private val exposeErrors: Boolean,
) : DataFetcherExceptionResolverAdapter() {

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
                    log.atError().setCause(apiError).addKeyValue("headers", HeaderDump.of(servletRequest(env)))
                        .log("GraphQL ApiError code={} errorName={} path={} msg={}", code.externalCode, code.name, path, apiError.message)
                code == ErrorCode.RATE_LIMITED || code == ErrorCode.QUOTA_EXCEEDED ||
                    code == ErrorCode.AUTH_PROVIDER_FAILED ->
                    log.warn("GraphQL ApiError code={} errorName={} path={} msg={}", code.externalCode, code.name, path, apiError.message)
                else ->
                    log.debug("GraphQL ApiError code={} errorName={} path={} msg={}", code.externalCode, code.name, path, apiError.message)
            }
            // extensions.retryAfterSec：限流类错误建议等待秒数（GraphQL 路径 HTTP header 不可用，规格 §4.4）
            val extensions = linkedMapOf<String, Any?>(
                "code" to code.externalCode,
                "errorName" to code.name,
            )
            apiError.retryAfterSec?.let { extensions["retryAfterSec"] = it }
            return GraphqlErrorBuilder.newError(env)
                .message(code.clientMessage(apiError.message, exposeErrors))
                .errorType(code.toGraphQLErrorType())
                .extensions(extensions)
                .build()
        }
        // 非 ApiError：未预期的程序异常（NPE/DB/Redis 等）。记 error 带 stack 便于排查；
        // 自己构造错误而不是返回 null——交给下游默认 resolver 会把异常信息带给客户端。
        log.atError().setCause(ex).addKeyValue("headers", HeaderDump.of(servletRequest(env)))
            .log("GraphQL unhandled exception. path={}", path)
        return GraphqlErrorBuilder.newError(env)
            .message(if (exposeErrors) (ex.message ?: ex.javaClass.simpleName) else GENERIC_SERVER_ERROR_MESSAGE)
            .errorType(ErrorType.INTERNAL_ERROR)
            .extensions(mapOf(
                "code" to ErrorCode.INTERNAL.externalCode,
                "errorName" to ErrorCode.INTERNAL.name,
            ))
            .build()
    }
}

/** data fetcher 线程上取不到 RequestContextHolder，从 DGS 上下文拿原始请求；取不到（非 HTTP/测试）返回 null。 */
private fun servletRequest(env: DataFetchingEnvironment): jakarta.servlet.http.HttpServletRequest? = runCatching {
    val data = com.netflix.graphql.dgs.context.DgsContext.getRequestData(env) as? com.netflix.graphql.dgs.internal.DgsWebMvcRequestData
    (data?.webRequest as? org.springframework.web.context.request.ServletRequestAttributes)?.request
}.getOrNull()

/** 将 ErrorCode 的 HTTP 语义映射到 Spring GraphQL ErrorType。 */
private fun com.ifmix.core.api.infra.http.ErrorCode.toGraphQLErrorType(): ErrorType = when {
    externalCode.startsWith("400") -> ErrorType.BAD_REQUEST
    externalCode.startsWith("401") -> ErrorType.UNAUTHORIZED
    externalCode.startsWith("403") -> ErrorType.FORBIDDEN
    externalCode.startsWith("404") -> ErrorType.NOT_FOUND
    // 429（RATE_LIMITED/QUOTA_EXCEEDED）、402（IAP）是客户端错误，归 BAD_REQUEST，
    // 不能落入 else 的 INTERNAL_ERROR（那会误导为服务端内部错误）。
    externalCode.startsWith("429") -> ErrorType.BAD_REQUEST
    externalCode.startsWith("402") -> ErrorType.BAD_REQUEST
    else -> ErrorType.INTERNAL_ERROR
}

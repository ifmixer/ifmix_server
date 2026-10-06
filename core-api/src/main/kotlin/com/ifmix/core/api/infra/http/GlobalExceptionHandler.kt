package com.ifmix.core.api.infra.http

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.servlet.NoHandlerFoundException

/** 统一异常处理：把异常映射为 GraphQL 形状的错误响应（[GraphQlErrorBody]）。 */
@RestControllerAdvice
class GlobalExceptionHandler(
    @param:Value("\${app.expose-errors:true}") private val exposeErrors: Boolean,
) {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiError::class)
    fun handleApiError(ex: ApiError, request: jakarta.servlet.http.HttpServletRequest? = null): ResponseEntity<GraphQlErrorBody> {
        val code = ex.errorCode
        // 集中分级记日志：5xx 服务端故障记 error(带 stack)；限流/第三方验证失败记 warn；
        // 其余纯客户端错误(400/401/404/403)记 debug，避免正常拒绝刷 warn。
        when {
            code.status.is5xxServerError ->
                log.atError().setCause(ex).addKeyValue("headers", HeaderDump.of(request))
                    .log("ApiError code={} errorName={} msg={}", code.externalCode, code.name, ex.message)
            code == ErrorCode.RATE_LIMITED || code == ErrorCode.QUOTA_EXCEEDED ||
                code == ErrorCode.AUTH_PROVIDER_FAILED ->
                log.warn("ApiError code={} errorName={} msg={}", code.externalCode, code.name, ex.message)
            else ->
                log.debug("ApiError code={} errorName={} msg={}", code.externalCode, code.name, ex.message)
        }
        val msg = code.clientMessage(ex.message, exposeErrors)
        // 线上 5xx 连 details 一起隐藏（details 同样可能带内部信息）
        val details = if (exposeErrors || !code.status.is5xxServerError) ex.details else null
        val extra = buildMap {
            if (ex.retryAfterSec != null) put("retryAfterSec", ex.retryAfterSec)
            if (details != null) put("details", details)
        }
        val body = GraphQlErrorBody.error(code.externalCode, code.name, msg, extra)
        val resp = ResponseEntity.status(code.status)
        if (code == ErrorCode.AI_UNAVAILABLE) resp.header("Retry-After", "60")
        return resp.body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<GraphQlErrorBody> {
        val msg = ex.bindingResult.fieldErrors
            .map { "${it.field}: ${it.defaultMessage}" }
            .sorted()
            .joinToString("; ")
        return errorStatus(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, msg.ifEmpty { "invalid request" })
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<GraphQlErrorBody> =
        errorStatus(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "malformed request body")

    /** 404：路径无映射 / 静态资源不存在。返回 404，warn 记录（不打 stack）。 */
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(ex: Exception): ResponseEntity<GraphQlErrorBody> {
        log.warn("No handler for request. msg={}", ex.message)
        return errorStatus(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "not found")
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: jakarta.servlet.http.HttpServletRequest? = null): ResponseEntity<GraphQlErrorBody> {
        log.atError().setCause(ex).addKeyValue("headers", HeaderDump.of(request)).log("Unhandled exception")
        val msg = if (exposeErrors) (ex.message ?: "error") else GENERIC_SERVER_ERROR_MESSAGE
        return errorStatus(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, msg)
    }

    private fun errorStatus(status: HttpStatus, code: ErrorCode, msg: String): ResponseEntity<GraphQlErrorBody> =
        ResponseEntity.status(status).body(GraphQlErrorBody.error(code.externalCode, code.name, msg))
}

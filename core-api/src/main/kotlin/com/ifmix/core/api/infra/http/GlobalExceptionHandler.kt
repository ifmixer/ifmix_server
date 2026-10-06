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

/** 统一异常处理：把异常映射为信封响应。 */
@RestControllerAdvice
class GlobalExceptionHandler(
    @param:Value("\${app.expose-errors:true}") private val exposeErrors: Boolean,
) {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiError::class)
    fun handleApiError(ex: ApiError, request: jakarta.servlet.http.HttpServletRequest? = null): ResponseEntity<*> {
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
        val resp = ResponseEntity.status(code.status)
        // Retry-After：retryAfterSec（限流类 429 / 降级 503）优先于 AI_UNAVAILABLE 的固定 60s。
        // 用 when 保证头只写一次——ResponseEntity.header() 是 append 语义，两条 if 会留下重复头。
        when {
            ex.retryAfterSec != null -> resp.header("Retry-After", ex.retryAfterSec.toString())
            code == ErrorCode.AI_UNAVAILABLE -> resp.header("Retry-After", "60")
        }
        // 错误路径 reqId：factory 已解析（attribute）优先，其次 x-req-id header，再无则 null。
        // 构建时直接带 reqId（rollout §3.1 改动 2：免逐点 copy，行为不变）。
        val reqId = reqIdOf(request)
        val body = if (details != null)
            Envelope(code.externalCode, msg, mapOf("details" to details).filter { it.value != null }, reqId)
        else
            Envelope(code.externalCode, msg, null, reqId)
        return resp.body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<Envelope<Nothing>> {
        val msg = ex.bindingResult.fieldErrors
            .map { "${it.field}: ${it.defaultMessage}" }
            .sorted()
            .joinToString("; ")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Envelope.error(ErrorCode.INVALID_REQUEST.externalCode, msg.ifEmpty { "invalid request" }))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<Envelope<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Envelope.error(ErrorCode.INVALID_REQUEST.externalCode, "malformed request body"))

    /** 404：路径无映射 / 静态资源不存在。返回 404，warn 记录（不打 stack）。 */
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(ex: Exception): ResponseEntity<Envelope<Nothing>> {
        log.warn("No handler for request. msg={}", ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Envelope.error(ErrorCode.NOT_FOUND.externalCode, "not found"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: jakarta.servlet.http.HttpServletRequest? = null): ResponseEntity<Envelope<Nothing>> {
        log.atError().setCause(ex).addKeyValue("headers", HeaderDump.of(request)).log("Unhandled exception")
        val msg = if (exposeErrors) (ex.message ?: "error") else GENERIC_SERVER_ERROR_MESSAGE
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Envelope(ErrorCode.INTERNAL.externalCode, msg, null, reqIdOf(request)))
    }

    /**
     * 错误路径 reqId 兜底（rollout §3.1）：factory 已解析的 attribute（[RequestHeaders.PARSED_REQ_ID_ATTR]）优先，
     * 其次 `x-req-id` header（factory 之前的失败，如 body 不可读），再无则 null（明文 curl 不带 reqId）。
     */
    private fun reqIdOf(request: jakarta.servlet.http.HttpServletRequest?): String? {
        if (request == null) return null
        return request.getAttribute(RequestHeaders.PARSED_REQ_ID_ATTR) as? String
            ?: request.getHeader(RequestHeaders.REQ_ID)?.takeIf { it.isNotBlank() }
    }
}

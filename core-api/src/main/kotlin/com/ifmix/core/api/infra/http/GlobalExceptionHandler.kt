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
    fun handleApiError(ex: ApiError): ResponseEntity<*> {
        val code = ex.errorCode
        if (code == ErrorCode.AI_UNAVAILABLE && ex.details != null) {
            return ResponseEntity.status(code.status)
                .header("Retry-After", "60")
                .body(Envelope.errorWithDetails(code.externalCode, ex.message ?: code.name, ex.details))
        }
        val body = if (ex.details != null) {
            Envelope.errorWithDetails(code.externalCode, ex.message ?: code.name, ex.details)
        } else {
            Envelope.error(code.externalCode, ex.message ?: code.name)
        }
        return ResponseEntity.status(code.status).body(body)
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
        log.warn("No handler for request: {}", ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Envelope.error(ErrorCode.NOT_FOUND.externalCode, "not found"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<Envelope<Nothing>> {
        log.error("Unhandled exception", ex)
        val msg = if (exposeErrors) (ex.message ?: "error") else "internal error"
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Envelope.error(ErrorCode.INTERNAL.externalCode, msg))
    }
}

package com.ifmix.api.core.common.http

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 统一异常处理：把异常映射为信封响应。 */
@RestControllerAdvice
class GlobalExceptionHandler(
    @param:Value("\${app.expose-errors:true}") private val exposeErrors: Boolean,
) {

    @ExceptionHandler(ApiError::class)
    fun handleApiError(ex: ApiError): ResponseEntity<Envelope<Nothing>> {
        val code = ex.errorCode
        return ResponseEntity.status(code.status)
            .body(Envelope.error(code.externalCode, ex.message ?: code.name))
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

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<Envelope<Nothing>> {
        val msg = if (exposeErrors) (ex.message ?: "error") else "internal error"
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Envelope.error(ErrorCode.INTERNAL.externalCode, msg))
    }
}

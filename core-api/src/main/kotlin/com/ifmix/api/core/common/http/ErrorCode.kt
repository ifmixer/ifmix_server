package com.ifmix.api.core.common.http

import org.springframework.http.HttpStatus

/** 语义错误码 -> 对外字符串码 + HTTP 状态。 */
enum class ErrorCode(val externalCode: String, val status: HttpStatus) {
    INVALID_REQUEST("400000", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("401000", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("403000", HttpStatus.FORBIDDEN),
    NOT_FOUND("404000", HttpStatus.NOT_FOUND),
    RATE_LIMITED("429000", HttpStatus.TOO_MANY_REQUESTS),
    IAP_VERIFY_FAILED("402000", HttpStatus.PAYMENT_REQUIRED),
    AI_UNAVAILABLE("503000", HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL("500000", HttpStatus.INTERNAL_SERVER_ERROR),
    APP_CONFIG_MISSING("400002", HttpStatus.BAD_REQUEST),
}

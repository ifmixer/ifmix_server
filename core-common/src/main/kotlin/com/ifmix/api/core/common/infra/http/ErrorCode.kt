package com.ifmix.api.core.common.infra.http

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
    AUTH_PROVIDER_FAILED("401001", ErrorCode.UNAUTHORIZED.status),

    /** access token 过期，客户端应调 refresh 重试 */
    TOKEN_EXPIRED("401002", HttpStatus.UNAUTHORIZED),
    /** refresh token 已失效（过期/被吊销），需重新登录 */
    REFRESH_EXPIRED("401003", HttpStatus.UNAUTHORIZED),

    /** 日配额用尽（与 RATE_LIMITED 区分：前者是短期限流，后者是日配额） */
    QUOTA_EXCEEDED("429001", HttpStatus.TOO_MANY_REQUESTS),
}

package com.ifmix.core.api.infra.http

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
    /** x-proto-version: 2 请求解密失败（格式/kid/认证任一不通过，不区分原因）。明文返回，客户端据此降级明文重试。 */
    WIRE_DECRYPT_FAILED("400003", HttpStatus.BAD_REQUEST),
    AUTH_PROVIDER_FAILED("401001", ErrorCode.UNAUTHORIZED.status),

    /** access token 过期，客户端应调 refresh 重试 */
    TOKEN_EXPIRED("401002", HttpStatus.UNAUTHORIZED),
    /** refresh token 已失效（过期/被吊销），需重新登录 */
    REFRESH_EXPIRED("401003", HttpStatus.UNAUTHORIZED),

    /** 日配额用尽（与 RATE_LIMITED 区分：前者是短期限流，后者是日配额） */
    QUOTA_EXCEEDED("429001", HttpStatus.TOO_MANY_REQUESTS),
}

/** 线上隐藏细节时，5xx / 未预期异常对客户端统一返回的通用文案（客户端按 code 做本地化）。 */
const val GENERIC_SERVER_ERROR_MESSAGE = "Something went wrong. Please try again later or contact us."

/**
 * 对客户端返回的错误消息。expose=false（线上）时 5xx 一律用通用文案，不透出内部细节
 * （异常 message、上游 AI/DB 报错等）；4xx 的消息本就是面向客户端写的，原样返回。
 */
fun ErrorCode.clientMessage(raw: String?, expose: Boolean): String =
    if (expose || !status.is5xxServerError) raw ?: name else GENERIC_SERVER_ERROR_MESSAGE

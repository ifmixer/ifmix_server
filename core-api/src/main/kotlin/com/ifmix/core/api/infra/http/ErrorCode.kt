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
    /** 加密请求解密失败（格式/kid/认证/解压超限任一不通过，不区分原因）。明文返回，客户端据此降级明文重试。 */
    WIRE_DECRYPT_FAILED("400003", HttpStatus.BAD_REQUEST),
    /** /customer/core/…（GraphQL 端点）请求未按 wire 协议加密（明文 / x-wirep-version 非法值）。app 未上线无兼容负担：线上强制加密，无降级。400004 原为 ts 时效预留（已暂缓），改作此用。 */
    WIRE_REQUIRED("400004", HttpStatus.BAD_REQUEST),
    AUTH_PROVIDER_FAILED("401001", ErrorCode.UNAUTHORIZED.status),

    /** access token 过期，客户端应调 refresh 重试 */
    TOKEN_EXPIRED("401002", HttpStatus.UNAUTHORIZED),
    /** refresh token 已失效（过期/被吊销），需重新登录 */
    REFRESH_EXPIRED("401003", HttpStatus.UNAUTHORIZED),

    /** 日配额用尽（与 RATE_LIMITED 区分：前者是短期限流，后者是日配额） */
    QUOTA_EXCEEDED("429001", HttpStatus.TOO_MANY_REQUESTS),

    /** install 平台证明失败：缺失/无效/replay/key 已绑定（createInstall 收到后应转 recover） */
    ATTESTATION_FAILED("403001", HttpStatus.FORBIDDEN),
    /** key 状态为 BLOCKED / RETIRED：禁止 recover / 新绑定（已签发 installToken 不吊销） */
    ATTEST_KEY_BLOCKED("403002", HttpStatus.FORBIDDEN),
    /** key 已绑定在别的 install 上：废弃 key，保留当前 installToken，不 recover */
    ATTEST_KEY_BOUND_TO_OTHER_INSTALL("409001", HttpStatus.CONFLICT),
    /** installToken 指向的 install 已不存在（attestExisting；客户端只在此码上清 install） */
    INSTALL_NOT_FOUND("404001", HttpStatus.NOT_FOUND),
    /** 平台证明临时不可用：验证方故障 / 配置无效 / ENFORCE 下客户端声明 UNAVAILABLE（可带 retryAfterSec） */
    ATTESTATION_UNAVAILABLE("503002", HttpStatus.SERVICE_UNAVAILABLE),
    /** 验签后的日窗口超限：createInstall 的 attested/unverified IP 日额度、attestExisting 新 key 日额度（必带 retryAfterSec=到 UTC 零点秒数） */
    INSTALL_DAILY_LIMITED("429002", HttpStatus.TOO_MANY_REQUESTS),
}

/** 线上隐藏细节时，5xx / 未预期异常对客户端统一返回的通用文案（客户端按 code 做本地化）。 */
const val GENERIC_SERVER_ERROR_MESSAGE = "Something went wrong. Please try again later or contact us."

/**
 * 对客户端返回的错误消息。expose=false（线上）时 5xx 一律用通用文案，不透出内部细节
 * （异常 message、上游 AI/DB 报错等）；4xx 的消息本就是面向客户端写的，原样返回。
 */
fun ErrorCode.clientMessage(raw: String?, expose: Boolean): String =
    if (expose || !status.is5xxServerError) raw ?: name else GENERIC_SERVER_ERROR_MESSAGE

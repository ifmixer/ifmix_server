package com.ifmix.core.api.infra.http

/** 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 映射为信封。 */
class ApiError(
    val errorCode: ErrorCode,
    message: String = errorCode.name,
    val details: Any? = null,
    /**
     * 限流类错误的建议等待秒数：429000=短窗口剩余秒数、429002=到 UTC 零点秒数（规格 §4.4，必带）；
     * 503002 可选。仅 GraphQL 输出（error extensions.retryAfterSec）；REST 不消费，保持现状。
     */
    val retryAfterSec: Long? = null,
) : RuntimeException(message)

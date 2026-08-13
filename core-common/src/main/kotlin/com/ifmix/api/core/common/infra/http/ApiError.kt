package com.ifmix.api.core.common.infra.http

/** 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 映射为信封。 */
class ApiError(
    val errorCode: ErrorCode,
    message: String = errorCode.name,
    val details: Any? = null,
) : RuntimeException(message)

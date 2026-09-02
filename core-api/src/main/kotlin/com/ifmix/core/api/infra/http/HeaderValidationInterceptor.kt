package com.ifmix.core.api.infra.http

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.util.UUID

/** 校验请求头：x-app-id 必填且为合法 UUID；x-client-platform 若存在须为合法枚举。 */
@Component
class HeaderValidationInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method == "OPTIONS") return true

        val appId = request.getHeader(RequestHeaders.APP_ID)
        if (appId.isNullOrBlank()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.APP_ID}: required")
        }
        if (!isValidUUID(appId)) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.APP_ID}: invalid input")
        }
        val platform = request.getHeader(RequestHeaders.CLIENT_PLATFORM)
        if (!platform.isNullOrBlank()) {
            try {
                ClientPlatform.fromHeader(platform)
            } catch (_: IllegalArgumentException) {
                throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.CLIENT_PLATFORM}: invalid input")
            }
        }
        return true
    }

    private fun isValidUUID(value: String): Boolean = try {
        UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

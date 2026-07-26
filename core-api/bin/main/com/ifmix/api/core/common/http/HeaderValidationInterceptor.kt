package com.ifmix.api.core.common.http

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/** 校验请求头：x-app-id 必填且为合法 ObjectId；x-client-platform 若存在须为合法枚举。 */
@Component
class HeaderValidationInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val appId = request.getHeader(RequestHeaders.APP_ID)
        if (appId.isNullOrBlank()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.APP_ID}: required")
        }
        if (!ObjectId.isValid(appId)) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.APP_ID}: invalid input")
        }
        val platform = request.getHeader(RequestHeaders.CLIENT_PLATFORM)
        if (!platform.isNullOrBlank()) {
            try {
                ClientPlatform.fromHeader(platform)
            } catch (e: IllegalArgumentException) {
                throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.CLIENT_PLATFORM}: invalid input")
            }
        }
        return true
    }
}

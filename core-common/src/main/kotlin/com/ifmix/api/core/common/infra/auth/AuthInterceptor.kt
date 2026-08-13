package com.ifmix.api.core.common.infra.auth

import com.ifmix.api.core.common.infra.http.RequestHeaders
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/** populateAuth：非阻塞验签。有效 access + aid 匹配 → 请求属性存 appUserId；否则匿名（不报错）。 */
@Component
class AuthInterceptor(private val jwt: AuthJwtService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val appId = request.getHeader(RequestHeaders.APP_ID) ?: return true
        val auth = request.getHeader("Authorization") ?: return true
        if (!auth.startsWith("Bearer ")) return true
        val token = auth.removePrefix("Bearer ").trim()
        try {
            val claims = jwt.verifyAccess(token, appId)
            claims?.let { request.setAttribute(ATTR_USER_ID, it) }
        } catch (_: Exception) {
            // 无效 token → 匿名，不报错
        }
        return true
    }

    companion object {
        const val ATTR_USER_ID = "com.ifmix.auth.userId"
    }
}

package com.ifmix.api.core.infra.http

/**
 * 从 HTTP 请求中提取真实客户端 IP。
 *
 * 优先级：X-Forwarded-For > X-Real-IP > RemoteAddress。
 * X-Forwarded-For 可能包含多个逗号分隔的 IP，取第一个（最靠近客户端的）。
 */
object ClientIpResolver {

    /**
     * 从 HttpServletRequest 中提取客户端 IP。
     */
    fun resolve(request: jakarta.servlet.http.HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            // 可能有多个 IP：client, proxy1, proxy2，取第一个
            return forwarded.split(",").firstOrNull()?.trim() ?: request.remoteAddr
        }
        val realIp = request.getHeader("X-Real-IP")
        if (!realIp.isNullOrBlank()) {
            return realIp.trim()
        }
        return request.remoteAddr
    }
}

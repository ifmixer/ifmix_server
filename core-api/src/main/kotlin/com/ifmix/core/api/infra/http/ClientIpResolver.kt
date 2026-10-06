package com.ifmix.core.api.infra.http

/**
 * 从 HTTP 请求中提取真实客户端 IP（限流/日志的可信信源）。
 *
 * 优先级：`CF-Connecting-IP`（CF 边缘注入，客户端伪造会被覆盖——P1 修复 2026-10-06）
 * > `X-Forwarded-For` 首段（仅直连/非 CF 环境可信；CF 链路上客户端可自带该头，**不可作限流依据**）
 * > `X-Real-IP` > `RemoteAddress`。
 *
 * 部署形态恒为 CF → 源站（见 docs/ops/DEPLOY.md），线上真实信源恒为 CF-Connecting-IP；
 * XFF 分支只为本地/测试直连场景保留。
 */
object ClientIpResolver {

    /**
     * 从 HttpServletRequest 中提取客户端 IP。
     */
    fun resolve(request: jakarta.servlet.http.HttpServletRequest): String {
        // CF 边缘注入的真实客户端 IP：客户端伪造的同名头在 CF 入口被覆盖，可信
        request.getHeader(RequestHeaders.CF_CONNECTING_IP)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            // 可能有多个 IP：client, proxy1, proxy2，取第一个（仅直连部署下可信；CF 后由上面分支接管）
            return forwarded.split(",").firstOrNull()?.trim() ?: request.remoteAddr
        }
        val realIp = request.getHeader("X-Real-IP")
        if (!realIp.isNullOrBlank()) {
            return realIp.trim()
        }
        return request.remoteAddr
    }
}

package com.ifmix.core.api.infra.http

import jakarta.servlet.http.HttpServletRequest

/**
 * ERROR 日志用：请求的全部 HTTP header（name 小写 → 值，多值以 ", " 拼接），作为 key-value `headers` 输出（JSON 里是对象）。
 * 凭证类 header 脱敏：Authorization 只留 scheme（`Bearer ***`），Cookie 等整体 `***`。
 */
object HeaderDump {
    private val MASKED = setOf("cookie", "set-cookie", "proxy-authorization", "x-api-key", "x-webhook-token")

    fun of(request: HttpServletRequest?): Map<String, String> {
        if (request == null) return emptyMap()
        return request.headerNames.toList().associate { raw ->
            val name = raw.lowercase()
            val value = request.getHeaders(raw).toList().joinToString(", ")
            name to when (name) {
                "authorization" -> value.substringBefore(' ', value).take(16) + " ***"
                in MASKED -> "***"
                else -> value
            }
        }.toSortedMap()
    }
}

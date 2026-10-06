package com.ifmix.core.api.infra.http

/**
 * 统一响应信封：{ reqId, code, msg, data }。code 为字符串（如 "200000"）。
 *
 * [reqId]（2026-10-06 新增）：回显本请求的 reqId（RPC 成功路径 = `meta.reqId`，缺省服务端生成值；
 * 错误路径由 GlobalExceptionHandler 从 request attribute / `x-req-id` header 兜底）。默认 null 兼容
 * GraphQL 路径与其他调用点（`ok()/error()` 不填）。
 */
data class Envelope<out T>(val code: String, val msg: String, val data: T?, val reqId: String? = null) {
    companion object {
        fun <T> ok(data: T): Envelope<T> = Envelope("200000", "success", data)
        /** RPC 成功路径统一入口（rollout §3.1 改动 2）：回显 reqId，免逐点 copy。 */
        fun <T> ok(reqId: String?, data: T): Envelope<T> = Envelope("200000", "success", data, reqId)
        fun error(code: String, msg: String): Envelope<Nothing> = Envelope(code, msg, null)
        fun errorWithDetails(code: String, msg: String, details: Any?): Envelope<*> =
            Envelope(code, msg, mapOf("details" to details).filter { it.value != null })
    }
}

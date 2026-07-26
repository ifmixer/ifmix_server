package com.ifmix.api.core.common.http

/** 统一响应信封：{ code, msg, data }。code 为字符串（如 "200000"）。 */
data class Envelope<out T>(val code: String, val msg: String, val data: T?) {
    companion object {
        fun <T> ok(data: T): Envelope<T> = Envelope("200000", "success", data)
        fun error(code: String, msg: String): Envelope<Nothing> = Envelope(code, msg, null)
    }
}

package com.ifmix.api.core.common.infra.http

/** 客户端平台枚举，对应请求头 x-client-platform。 */
enum class ClientPlatform {
    ANDROID,
    IOS,
    WEB;

    companion object {
        /** 空/空白返回 null；非法值抛 IllegalArgumentException（调用方转成 400）。 */
        fun fromHeader(raw: String?): ClientPlatform? {
            if (raw.isNullOrBlank()) return null
            return valueOf(raw.trim().uppercase())
        }
    }
}

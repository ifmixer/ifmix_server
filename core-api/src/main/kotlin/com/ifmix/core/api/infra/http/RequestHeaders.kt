package com.ifmix.core.api.infra.http

/** 请求头名常量。 */
object RequestHeaders {
    /**
     * 请求 id（请求头 + 响应头同名）：任意字符串（客户端自定，服务端不校验格式）；缺省由服务端生成 UUID。客户端可自带，能解析为 UUID 则直接用，
     * 否则服务端生成；响应头回传最终值，与日志里的 rid 一致。
     */
    const val REQ_ID = "x-req-id"
    const val PROJECT_ID = "x-project-id"
    const val INSTALL_ID = "x-install-id"
    const val LOCALE = "x-locale"
    const val CURRENCY = "x-currency"
    const val COUNTRY = "x-country"
    const val APP_VERSION = "x-app-version"
    const val OTA_VERSION = "x-ota-version"
    const val CLIENT_PLATFORM = "x-client-platform"
    /** Cloudflare bot score（1-99，越低越像 bot）。需在 CF Transform Rule 里把 cf.bot_management.score 写入该请求头。 */
    const val CF_BOT_SCORE = "cf-bot-score"
}

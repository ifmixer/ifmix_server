package com.ifmix.core.api.infra.http

/** 请求头名常量。 */
object RequestHeaders {
    /** 响应头：服务端为每个请求生成的请求 id（与日志里的 rid 一致，客户端报障时回传）。 */
    const val REQUEST_ID = "x-request-id"
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

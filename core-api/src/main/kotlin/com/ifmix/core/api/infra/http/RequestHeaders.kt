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
    /**
     * 线协议版本：缺省/1 = 明文；3 = body 加密（RFC 9180 HPKE，见 WireCryptoFilter）。响应头同名回传 3 表示响应已加密。
     * 旧名 `x-proto-version`（v2 从未上线）已随 v2 一并移除，v3 用新名 `x-wirep-version`（客户端已同步实现）。
     */
    const val WIREP_VERSION = "x-wirep-version"
    /** Cloudflare bot score（1-99，越低越像 bot）。需在 CF Transform Rule 里把 cf.bot_management.score 写入该请求头。 */
    const val CF_BOT_SCORE = "cf-bot-score"
    /** Cloudflare 注入的真实客户端 IP（边缘设置，客户端伪造的该头会被 CF 覆盖）。IP 限流/日志的可信信源。 */
    const val CF_CONNECTING_IP = "cf-connecting-ip"
    /**
     * dev 专用（仅 local profile 被消费，见 [DevRpcHeaderAdapter]）：RequestMeta JSON 字符串，
     * 作 body.meta 的字段级底座；**不含凭证**（accessToken 字段被忽略，凭证走 Authorization header）。
     * prod 不注册适配器，该头不被识别。
     */
    const val DEV_REQ_META = "x-req-meta"

    /**
     * RPC 请求 attribute key（非 header）：[ActionContextFactory.fromRpc] 解析出 requestId 后回写，
     * 供 [GlobalExceptionHandler] 在错误路径构造 Envelope.reqId（factory 之前的失败用 [REQ_ID] header 兜底）。
     */
    const val PARSED_REQ_ID_ATTR = "com.ifmix.parsed.reqId"
}

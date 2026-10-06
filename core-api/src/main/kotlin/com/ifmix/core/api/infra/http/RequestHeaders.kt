package com.ifmix.core.api.infra.http

/** 请求头名常量。 */
object RequestHeaders {
    /**
     * 请求 id（请求头 + 响应头同名）：任意字符串（客户端自定，服务端不校验格式）；缺省由服务端生成 UUID。客户端可自带，能解析为 UUID 则直接用，
     * 否则服务端生成；响应头回传最终值，与日志里的 rid 一致。
     */
    /**
     * 线协议版本：缺省/1 = 明文；3 = body 加密（RFC 9180 HPKE，见 WireCryptoFilter）。响应头同名回传 3 表示响应已加密。
     * 旧名 `x-proto-version`（v2 从未上线）已随 v2 一并移除，v3 用新名 `x-wirep-version`（客户端已同步实现）。
     */
    const val WIREP_VERSION = "x-wirep-version"
    /** Cloudflare bot score（1-99，越低越像 bot）。需在 CF Transform Rule 里把 cf.bot_management.score 写入该请求头。 */
    const val CF_BOT_SCORE = "cf-bot-score"
    /**
     * dev/调试专用：明文请求（仅 `app.wire-crypto.mode=optional`）把上下文 meta 以一个 JSON header 传入
     * （key 与加密 body.meta 相同的 header 名，如 `{"x-locale":"zh-CN","x-currency":"USD"}`），
     * 服务端合并为伪 header（见 WireCryptoFilter）。加密请求不需要它（meta 在加密 body 里）。
     */
    const val REQ_META = "x-req-meta"
}

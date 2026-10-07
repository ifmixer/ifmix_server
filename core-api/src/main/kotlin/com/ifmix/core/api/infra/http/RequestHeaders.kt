package com.ifmix.core.api.infra.http

/** 请求头名常量。 */
object RequestHeaders {
    /**
     * 线协议标记头：**加密请求不传**（加密判定靠 `Content-Type: application/octet-stream`）；
     * 明文请求传 `1`。头存在且非 1 → 400004。响应不回传（加密响应由 Content-Type 标识）。
     * 旧名 `x-proto-version`（v2 从未上线）已移除，现用 `x-wirep-version`。
     */
    const val WIREP_VERSION = "x-wirep-version"
    /** Cloudflare bot score（1-99，越低越像 bot）。需在 CF Transform Rule 里把 cf.bot_management.score 写入该请求头。 */
    const val CF_BOT_SCORE = "cf-bot-score"
    /**
     * dev/调试专用（仅 `app.wire-crypto.mode=optional`）：明文请求把上下文 meta 以一个 JSON header 传入
     * （`RequestMeta` JSON，普通字段名，如 `{"projectId":"…","locale":"zh-CN","currency":"USD"}`，不含凭证），
     * 服务端解析挂到 request attribute（见 WireCryptoFilter）。加密请求不需要它（meta 在加密 body 里）。
     */
    const val REQ_META = "x-req-meta"
}

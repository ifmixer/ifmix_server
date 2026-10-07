package com.ifmix.core.api.infra.http

/**
 * RPC 请求元信息（加密 body 信封的 `meta` 段 / 明文 dev 通道的 `x-req-meta` header，二者同构）。
 *
 * key 一律用普通字段名（`projectId` / `locale` …，不是 header 名）；全部可空（缺失 = 未提供）。
 * 未知字段由 Jackson 3 默认忽略（FAIL_ON_UNKNOWN_PROPERTIES 3.x 起默认关闭）——
 * 客户端先于服务端上线时新增字段不破坏老请求。
 * 值统一为字符串（wire 结构约束；类型转换由 RequestParser 统一做）。
 * **不含凭证**：token 走加密 body 顶层 `authorization` / 标准 `Authorization` header
 *（[com.ifmix.core.api.infra.auth.RequestParser.parseAuthorization]），不在 meta 里。
 */
data class RequestMeta(
    /** 请求 id（客户端自定；缺失时由服务端生成 UuidV7，见 LogContext.start）。 */
    val reqId: String? = null,
    val projectId: String? = null,
    val locale: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val appVersion: String? = null,
    val otaVersion: String? = null,
    val clientPlatform: String? = null,
) {
    companion object {
        /** [com.ifmix.core.api.infra.auth.RequestParser.parseMeta] 把解析结果挂到 request attribute 的 key。 */
        const val ATTR_META = "com.ifmix.parsed.meta"
    }
}

package com.ifmix.api.core.infra.http

import java.util.UUID

/**
 * 请求上下文 — 从 HTTP header 解析的纯请求信息。
 * 一个 HTTP request 一个实例，多个 operation 共享。
 */
data class RequestContext(
    val appId: UUID? = null,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val clientIp: String? = null,
)

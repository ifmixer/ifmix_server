package com.ifmix.api.core.infra.http

import java.util.UUID

/**
 * 请求上下文 — 从 HTTP header 解析的纯请求信息。
 * 一个 HTTP request 一个实例，多个 operation 共享。
 */
data class RequestContext(
    val appId: UUID? = null,
    /** 主体 id 归一：actorType==customer 时 = actorId。null = 匿名/未认证。 */
    val customerId: UUID? = null,
    /** 主体类型："customer" / 未来 "manager"。未认证时 null。 */
    val actorType: String? = null,
    /** 是否匿名主体（token ano claim）。 */
    val anonymous: Boolean = false,
    val locale: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val clientIp: String? = null,
)

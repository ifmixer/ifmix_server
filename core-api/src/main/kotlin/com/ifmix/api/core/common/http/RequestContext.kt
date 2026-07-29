package com.ifmix.api.core.common.http

/**
 * 不可变请求上下文，显式作为方法参数在 controller -> service -> repo 之间传递。
 * appId 非空（由请求头校验保证）；其余可空。userId 预留给未来 auth 模块。
 */
data class RequestContext(
    val appId: String,
    val installId: String? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: String? = null,
    val clientIp: String? = null,
    /** true = 允许读从库（仅影响 ReadWriteRoutingDataSource）。事务内自动走主库。 */
    val readFromReplica: Boolean = false,
)

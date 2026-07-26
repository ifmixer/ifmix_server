package com.ifmix.api.core.common.http

import com.mongodb.ReadPreference

/**
 * 不可变请求上下文，显式作为方法参数在 controller -> service -> repo 之间传递。
 * appId 非空（由请求头校验保证）；其余可空。userId 预留给未来 auth 模块。
 *
 * readPreference：本请求的读偏好，默认 primaryPreferred（读主库、主不可用时回落从库，
 * 天然满足写后回读）。需要把某些只读请求分流到从库时，服务端 ctx.copy(readPreference = ...)。
 * 事务内的读由 CRUDRepository 强制走主库，覆盖此设置。
 */
data class RequestContext(
    val appId: String,
    val installId: String? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: String? = null,
    val readPreference: ReadPreference = ReadPreference.primaryPreferred(),
)

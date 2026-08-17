package com.ifmix.api.core.common.http

import com.mongodb.ReadPreference

/** BFF 类型：customer 或 admin。 */
enum class Bff {
    CUSTOMER, ADMIN
}

/** Actor 类型：区分 admin / 登录客户 / 匿名 install / API key。 */
enum class ActorType {
    ADMIN,
    CUSTOMER_USER,
    CUSTOMER_INSTALL,
    API_KEY,
}

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
    val operationId: String? = null,   // x-op-id header 或 persisted query name
    val installId: String? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: String? = null,
    val readPreference: ReadPreference = ReadPreference.primaryPreferred(),

    // --- 从 GraphQLRequestContext 合并过来 ---
    val bff: Bff = Bff.CUSTOMER,
    val permissions: Set<String> = emptySet(),
    val actorType: ActorType = ActorType.CUSTOMER_INSTALL,

    // --- 请求级内存缓存，避免同一请求内重复查库。key 自定义。 ---
    val requestCache: MutableMap<String, Any?> = mutableMapOf(),
) {
    /** 根据 actorType 返回当前请求的 actor id。 */
    fun getActorId(): String = when (actorType) {
        ActorType.ADMIN -> userId ?: error("admin must have userId")
        ActorType.CUSTOMER_USER -> userId ?: error("customer user must have userId")
        ActorType.CUSTOMER_INSTALL -> installId ?: error("customer install must have installId")
        ActorType.API_KEY -> appId
    }
}

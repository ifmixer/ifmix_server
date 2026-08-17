package com.ifmix.api.core.common.http

import com.mongodb.ReadPreference

/**
 * 统一请求上下文，贯穿 controller/fetcher → service → repo。
 * 合并了原 GraphQLRequestContext 的 bff/permissions 字段。
 *
 * readPreference：本请求的读偏好，默认 primaryPreferred。事务内由 CRUDRepository 强制走主库。
 * requestCache：请求级内存缓存，避免同一请求内重复查库/Redis。
 */
data class RequestContext(
    val appId: String,
    val operationId: String? = null,
    val installId: String? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: String? = null,
    val readPreference: ReadPreference = ReadPreference.primaryPreferred(),
    val bff: Bff = Bff.CUSTOMER,
    val permissions: Set<String> = emptySet(),
    val actorType: ActorType = ActorType.CUSTOMER_INSTALL,
    val requestCache: MutableMap<String, Any?> = mutableMapOf(),
)

enum class Bff { CUSTOMER, ADMIN }

enum class ActorType {
    ADMIN,
    CUSTOMER_USER,
    CUSTOMER_INSTALL,
    API_KEY,
}

/** 返回当前 actor 的唯一标识 id。 */
fun RequestContext.getActorId(): String = when (actorType) {
    ActorType.ADMIN -> userId ?: error("admin must have userId")
    ActorType.CUSTOMER_USER -> userId ?: error("customer user must have userId")
    ActorType.CUSTOMER_INSTALL -> installId ?: error("customer install must have installId")
    ActorType.API_KEY -> appId
}

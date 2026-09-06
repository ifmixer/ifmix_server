package com.ifmix.core.api.infra.http

import com.ifmix.core.api.entity.common.ActorType
import org.babyfish.jimmer.sql.kt.KSqlClient
import java.util.UUID

/**
 * 操作上下文 — per-operation。
 *
 * 两种构造来源：
 *  - GraphQL 请求：由 [com.ifmix.core.api.infra.graphql.OperationContextProvider.fromDfe] 从
 *    HttpServletRequest 解析 header/token（并按需校验、失败即抛）后构造，持有已解析的值。
 *  - webhook / 内部调用：直接构造并塞入 appId/actorId（无 HTTP 请求）。
 *
 * 只持有「已解析成功的值」，不含错误状态——token 过期/无效等在 fromDfe 解析+校验时即时抛 ApiError。
 */
data class OperationContext(
    val appId: UUID? = null,
    /** 主体 id（token sub）。null = 未认证。 */
    val actorId: UUID? = null,
    /** 主体类型：10=customer / 20=manager。未认证时 null。 */
    val actorType: ActorType? = null,
    /** 是否匿名主体（token ano claim）。 */
    val anonymous: Boolean = false,
    val locale: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val clientIp: String? = null,
    // ===== 操作元信息 =====
    val opName: String? = null,
    val isMutation: Boolean = false,
    /** true = 优先走 reader；mutation 时默认为 false，query 时默认为 true */
    val preferReader: Boolean = !isMutation,
    // ===== 全局事务支持 =====
    /**
     * 全局事务 KSqlClient（由 GlobalTxRunner 在 DataFetcher 层设置）。
     * ModuleCtxFactory 构建 ModuleCtx 时：若已有全局事务则复用，否则走 router 选择。
     */
    val globalTxSql: KSqlClient? = null,
    val inGlobalTx: Boolean = false,
) {
    /** true = 允许读缓存。mutation 时为 false，避免脏读。 */
    val readCache get() = !isMutation

    fun mustGetAppId() = appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
    fun mustGetActorId() = actorId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
}

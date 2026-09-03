package com.ifmix.core.api.infra.http

import org.babyfish.jimmer.sql.kt.KSqlClient

/**
 * 操作上下文 — per-operation，由 OperationContextProvider 从 DFE 构建。
 * 包含请求信息 + 操作元信息，不含基础设施决策。
 */
data class OperationContext(
    val req: RequestContext,
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
    // ===== 便捷委托 =====
    val appId get() = req.appId
    val actorId get() = req.actorId
    val actorType get() = req.actorType
    val anonymous get() = req.anonymous
    val locale get() = req.locale
    val currency get() = req.currency
    val country get() = req.country
    val clientPlatform get() = req.clientPlatform
    val clientIp get() = req.clientIp

    /** true = 允许读缓存。mutation 时为 false，避免脏读。 */
    val readCache get() = !isMutation

    fun mustGetAppId() = req.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
    fun mustGetActorId() = req.actorId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
}

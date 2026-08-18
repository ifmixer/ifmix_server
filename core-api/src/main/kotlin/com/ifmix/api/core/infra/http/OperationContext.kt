package com.ifmix.api.core.infra.http

import org.jooq.DSLContext

/**
 * 操作上下文 — per-operation，由 OperationContextProvider 从 DFE 构建。
 * 包含请求信息 + 操作元信息，不含基础设施决策。
 */
data class OperationContext(
    val req: RequestContext,
    val opName: String? = null,
    val isMutation: Boolean = false,
    // ===== 全局事务支持 =====
    /**
     * 全局事务 DSLContext（由 GlobalTxRunner 在 DataFetcher 层设置）。
     * FacadeService 构建 SvcCtx 时：若已有全局事务则复用，否则使用默认 DSL。
     */
    val globalTxDsl: DSLContext? = null,
    val inGlobalTx: Boolean = false,
) {
    // ===== 便捷委托 =====
    val appId get() = req.appId
    val installId get() = req.installId
    val userId get() = req.userId
    val lang get() = req.lang
    val currency get() = req.currency
    val country get() = req.country
    val clientPlatform get() = req.clientPlatform
    val clientIp get() = req.clientIp

    /** true = 允许读缓存。mutation 时为 false，避免脏读。 */
    val readCache get() = !isMutation

    fun mustGetAppId() = req.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
    fun mustGetUserId() = req.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
    fun mustGetInstallId() = req.installId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-install-id is required")
}

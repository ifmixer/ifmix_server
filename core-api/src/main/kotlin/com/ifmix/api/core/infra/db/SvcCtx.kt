package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.http.OperationContext
import org.jooq.DSLContext

/**
 * 服务层上下文 — per-service-call，由 Service 构建。
 * 包含 operation 信息 + 当前集群的 DSLContext + 事务状态。
 */
data class SvcCtx(
    val op: OperationContext,
    val dsl: DSLContext,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    companion object {
        /** 临时兼容：旧代码仍引用 DEFAULT。迁移完成后删除。 */
        lateinit var DEFAULT: SvcCtx
    }

    // ===== 便捷委托 =====
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readCache get() = op.readCache

    fun mustGetAppId() = op.mustGetAppId()
    fun mustGetUserId() = op.mustGetUserId()
    fun mustGetInstallId() = op.mustGetInstallId()
}

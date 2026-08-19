package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.http.OperationContext
import org.babyfish.jimmer.sql.kt.KSqlClient

/**
 * 服务层上下文 — per-service-call，由 Service 构建。
 * 包含 operation 信息 + 当前集群的 KSqlClient + 事务状态。
 */
data class SvcCtx(
    val op: OperationContext,
    val sql: KSqlClient,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    // ===== 便捷委托 =====
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readCache get() = op.readCache

    fun mustGetAppId() = op.mustGetAppId()
    fun mustGetUserId() = op.mustGetUserId()
    fun mustGetInstallId() = op.mustGetInstallId()
}

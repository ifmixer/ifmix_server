package com.ifmix.core.api.infra.db

import com.ifmix.core.api.infra.http.OperationContext
import org.babyfish.jimmer.sql.kt.KSqlClient

/**
 * 模块层上下文 — per-service-call，由 Facade 通过 ModuleCtxFactory 构建。
 * 包含 operation 信息 + 当前集群的 KSqlClient + 事务状态。
 */
data class ModuleCtx(
    val op: OperationContext,
    val sql: KSqlClient,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    // ===== 便捷委托 =====
    val appId get() = op.appId
    val customerId get() = op.customerId
    val actorType get() = op.actorType
    val anonymous get() = op.anonymous
    val readCache get() = op.readCache

    fun mustGetAppId() = op.mustGetAppId()
    fun mustGetCustomerId() = op.mustGetCustomerId()
}

/** 一个集群的 writer + reader KSqlClient 对 */
data class ClusterSqlPair(val writer: KSqlClient, val reader: KSqlClient)

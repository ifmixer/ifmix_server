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
    val projectId get() = op.projectId
    val actorId get() = op.actorId
    val actorType get() = op.actorType
    val anonymous get() = op.anonymous
    val readCache get() = op.readCache

    fun mustGetProjectId() = op.mustGetProjectId()
    fun mustGetActorId() = op.mustGetActorId()
}

/** 一个集群的 writer + reader KSqlClient 对 */
data class ClusterSqlPair(val writer: KSqlClient, val reader: KSqlClient)

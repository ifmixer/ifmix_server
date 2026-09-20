package com.ifmix.core.api.infra.db

import com.ifmix.core.api.infra.http.ActionContext
import org.babyfish.jimmer.sql.kt.KSqlClient

/**
 * 模块层上下文 — per-service-call，由 Facade 通过 ModuleCtxFactory 构建。
 * 包含 action 信息 + 当前集群的 KSqlClient + 事务状态。
 */
data class ModuleCtx(
    val action: ActionContext,
    val sql: KSqlClient,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    // ===== 便捷委托 =====
    val projectId get() = action.projectId
    val actorId get() = action.actorId
    val actorType get() = action.actorType
    val anonymous get() = action.anonymous
    val readCache get() = action.readCache

    fun mustGetProjectId() = action.mustGetProjectId()
    fun mustGetActorId() = action.mustGetActorId()
}

/** 一个集群的 writer + reader KSqlClient 对 */
data class ClusterSqlPair(val writer: KSqlClient, val reader: KSqlClient)

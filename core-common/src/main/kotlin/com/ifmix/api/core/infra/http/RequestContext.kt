package com.ifmix.api.core.infra.http

import com.ifmix.api.core.infra.db.RepoContext
import java.util.UUID

/**
 * 操作上下文 — 贯穿 Controller → Service → Repository 的全链路参数容器。
 *
 * 由 OperationContextArgumentResolver 从 HTTP 请求中组装。
 * 非 HTTP 入口（定时任务、MQ 消费者）手动构造。
 * 将来扩展：多集群 DB handle、显式事务等。
 */
data class OperationContext(
    val appId: UUID? = null,
    val installId: UUID? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: UUID? = null,
    val clientIp: String? = null,
    /** 业务数据目标集群 ID（请求入口由 ClusterRouter 解析） */
    val clusterId: String = "default",
    /** 全局集群 ID（auth 等共享数据，初期 = clusterId） */
    val globalClusterId: String = "default",
    /** true = 读操作走 reader 节点 */
    val readFromReplica: Boolean = false,
) {
    /** 业务数据 RepoContext */
    val repoCtx: RepoContext
        get() = RepoContext(clusterId = clusterId, preferReader = readFromReplica)

    /** Auth / 全局数据 RepoContext */
    val globalRepoCtx: RepoContext
        get() = RepoContext(clusterId = globalClusterId, preferReader = readFromReplica)
}

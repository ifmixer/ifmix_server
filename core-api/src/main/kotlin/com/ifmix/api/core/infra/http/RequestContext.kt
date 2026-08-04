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
    /** true = 允许读从库（仅影响 ReadWriteRoutingDataSource）。事务内自动走主库。 */
    val readFromReplica: Boolean = false,
) {
    /**
     * Repository 层专用上下文。
     * 当前实现为单例 DEFAULT；将来多集群路由时可根据 appId 等信息动态构造。
     */
    val repoCtx: RepoContext get() = RepoContext.DEFAULT
}

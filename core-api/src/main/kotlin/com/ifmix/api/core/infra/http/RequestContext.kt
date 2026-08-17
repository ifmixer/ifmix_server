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
    /** true = 允许读从库。mutation 时为 false。 */
    val readFromReplica: Boolean = false,
    /** GraphQL operation field name */
    val opName: String? = null,
    /** true = mutation */
    val isMutation: Boolean = false,
    /** 是否允许读缓存。mutation 时为 false，避免脏读。 */
    val readCache: Boolean = !isMutation,
    /** Repository 层上下文（持有 DSLContext，按 appId/集群路由） */
    val repoCtx: RepoContext = RepoContext.DEFAULT,
)

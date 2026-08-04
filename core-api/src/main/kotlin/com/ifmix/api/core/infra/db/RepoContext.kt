package com.ifmix.api.core.infra.db

/**
 * Repository 层专用上下文 — 只含基础设施引用，不含业务信息。
 * 将来多集群时可指定具体的 DB handle / transaction status。
 *
 * 设计意图：repo 方法签名不再依赖 OperationContext（HTTP 层概念），
 * 而是接收这个纯基础设施上下文，保持分层边界清晰。
 */
class RepoContext {
    // 当前暂时为空，将来加：
    // val sql: KSqlClient? = null,
    // val tx: TransactionStatus? = null,

    companion object {
        val DEFAULT = RepoContext()
    }
}

package com.ifmix.api.core.infra.db

import org.jooq.DSLContext

/**
 * Repository 层专用上下文 — 持有当前 operation 对应的 DSLContext。
 *
 * 由 OperationContextProvider 在构建 OperationContext 时根据 appId 解析集群，
 * 创建对应的 DSLContext 放入 repoCtx。
 *
 * Repo 方法通过 repoCtx.dsl 执行 SQL，不自己持有 DSLContext。
 * 这样支持：
 * - 多集群路由（不同 appId → 不同 DataSource → 不同 DSLContext）
 * - 读写分离（mutation → 主库 DSLContext，query → 从库 DSLContext）
 */
data class RepoContext(
    val dsl: DSLContext,
    val clusterId: String = "default",
    /** true = 当前已在事务中（由外层 TxRunner.withTx 开启） */
    val inTransaction: Boolean = false,
) {
    companion object {
        /**
         * 临时兼容：旧代码仍引用 DEFAULT。
         * 迁移完成后删除此字段，所有 RepoContext 都应由 OperationContextProvider 构建。
         */
        lateinit var DEFAULT: RepoContext
    }
}

package com.ifmix.api.core.common.infra.db

/**
 * Repository 层基础设施上下文 — 决定数据路由。
 *
 * - clusterId: 目标 PG 集群
 * - preferReader: true = reader 节点（autoCommit，无事务）; false = writer 节点
 */
data class RepoContext(
    val clusterId: String,
    val preferReader: Boolean = false,
) {
    companion object {
        /** 向后兼容：初期所有请求走 default cluster writer */
        val DEFAULT = RepoContext(clusterId = "default", preferReader = false)
    }
}

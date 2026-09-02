package com.ifmix.core.job.customer

/**
 * 匿名 Customer 清理的「是否应删」纯判定（R1 防误删入口）。
 *
 * 抽成纯函数便于独立断言测试（不依赖 DB / Spring）。cleaner 先用 SQL 过滤出两类候选，
 * 再对每个候选拉取 hasValidToken / hasActiveSubscription，最后交给本函数裁决。
 *
 * 绝不删「正常已登录用户」：anonymous=false 且 merged=false 的组合永远返回 false。
 */
object AnonymousCleanupDecision {

    /** 候选主体的清理相关状态快照。 */
    data class CustomerState(
        /** 是否匿名（未转正）。 */
        val anonymous: Boolean,
        /** 是否已合并（mergedTo != null）。 */
        val merged: Boolean,
        /** 已合并 tombstone 是否超过审计窗口（updatedAt < cutoff）。仅 merged 时有意义。 */
        val tombstoneExpired: Boolean,
        /** 名下是否存在有效 refresh token。 */
        val hasValidToken: Boolean,
        /** 名下是否存在 active=true 的订阅。 */
        val hasActiveSubscription: Boolean,
    )

    /**
     * 是否应物理删除该 customer。
     *
     * 删除仅命中两类：
     *  1. 未合并匿名僵尸：anonymous=true AND !merged AND 无有效 token。
     *  2. 已合并 tombstone：merged AND 超审计窗口。
     * 任一类命中，若名下有 active 订阅则一律跳过（避免误删「匿名却付费」边界数据）。
     */
    fun shouldDelete(s: CustomerState): Boolean {
        if (s.hasActiveSubscription) return false

        val isZombie = s.anonymous && !s.merged && !s.hasValidToken
        val isTombstone = s.merged && s.tombstoneExpired
        return isZombie || isTombstone
    }
}

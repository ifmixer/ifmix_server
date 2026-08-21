package com.ifmix.api.core.entity.demo

/**
 * Todo 子项统计指标。
 * 由 DataLoader 批量 SQL 聚合产出，不对应单表行。
 */
data class TodoMetrics(
    val itemCount: Int = 0,
    val pendingItemCount: Int = 0,
    val finishedItemCount: Int = 0,
)

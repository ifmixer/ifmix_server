package com.ifmix.core.api.modules.demo

/** Todo 的 items 三项计数（repo 聚合返回、QueryService 组装 [com.ifmix.core.api.dto.demo.TodoRes] 用）。 */
data class TodoItemCounts(
    val itemCount: Int,
    val pendingCount: Int,
    val finishCount: Int,
)

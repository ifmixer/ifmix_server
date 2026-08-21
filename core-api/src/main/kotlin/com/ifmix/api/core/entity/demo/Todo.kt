package com.ifmix.api.core.entity.demo

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import com.ifmix.api.core.modules.demo.repo.TodoItemMetricsCounts
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Todo 领域模型 (Jimmer entity)。
 */
@Entity
@Table(name = "core_todo")
interface Todo : AppScopedProps, SoftDeletableProps {
    @Id
    val id: UUID

    val installId: UUID?
    val userId: UUID?
    val title: String
    val done: Boolean
    val note: String?

    @Serialized
    val meta: Map<String, Any?>?

    // ===== Jimmer 计算字段演示 =====

    /**
     * 复合计算字段 — 一次 resolve 返回整个 metrics 对象。
     * Resolver 批量查询后返回 Map<UUID, TodoItemMetricsCounts>。
     */
    @Transient(ref = "todoMetricsTransientResolver")
    val jimmerMetrics: TodoItemMetricsCounts

    /**
     * 独立计算字段 — 每个字段一个 Resolver，各自 resolve。
     * 实际场景中如果三个字段总是一起查，应该用复合字段；
     * 这里为了演示独立 Resolver 的写法才拆开。
     */
    @Transient(ref = "todoItemCountResolver")
    val jimmerCount: Int

    @Transient(ref = "todoPendingCountResolver")
    val jimmerPendingCount: Int

    @Transient(ref = "todoFinishedCountResolver")
    val jimmerFinishCount: Int
}

package com.ifmix.core.api.dto.ai

import java.time.Instant
import java.util.UUID

/**
 * 后台 DeepResearch 任务的不可变上下文：mutation 事务内创建记录后、事务提交后传给后台执行。
 * 后台任务脱离 HTTP 请求线程/请求事务/请求 ActionContext，仅依赖此快照（设计 §6.1）。
 */
data class DeepResearchTaskContext(
    val projectId: String,
    val customerId: UUID,
    val deepResearchId: UUID,
    val scanRecordId: UUID,
    /** images 的轻量载体（避免直接依赖 Jimmer 实体跨线程）。 */
    val images: List<ImageRefItem>,
    val locale: String?,
    val country: String?,
    val currency: String?,
    val promptVersion: String,
    val createdAt: Instant,
    /** 发起本次 DeepResearch 请求的 install；不能从 scan 原始 install 推断。 */
    val installId: UUID? = null,
    /** 创建任务时由前端 mutation 传入的 push enrollment flag。 */
    val deepResearchPushEnabled: Boolean = false,
) {
    data class ImageRefItem(val key: String, val category: Int?)
}

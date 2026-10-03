package com.ifmix.core.api.dto.ai

import java.time.Instant
import java.util.UUID

/** Scan mutation 提交后交给后台任务的不可变快照。 */
data class ScanTaskContext(
    val projectId: String,
    val customerId: UUID,
    val installId: UUID?,
    val scanId: UUID,
    val locale: String?,
    val country: String?,
    val currency: String?,
    val images: List<ImageRefItem>,
    val collected: Boolean,
    val promptVersion: String,
    val createdAt: Instant,
    /** 创建任务时由前端 mutation 传入的 push enrollment flag。 */
    val scanResultPushEnabled: Boolean = false,
) {
    data class ImageRefItem(
        val imageKey: String,
        val category: Int?,
        val mediaType: String?,
    )
}

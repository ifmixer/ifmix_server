package com.ifmix.core.api.dto.ai

import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * DeepResearch 结果 doc（存 R2 bucket u2）的组装纯函数与常量。
 *
 * doc 结构（设计 §5）：docVersion / promptVersion / scanRecordSnapshot（AI 回写后语义的完整
 * scan_record 快照，纯分析留档，前端不以其覆盖本地）/ deepResearchSnapshot / 顶级 premiumResult。
 */
object DeepResearchDocs {

    /** doc JSON 结构 schema 版本（区别于 promptVersion）。DB 冗余存一份（doc_version 列）。 */
    const val CURRENT_DOC_VERSION = 1

    /** R2 bucket id（私有，仅 presignDownload；dev=u2dev / prod=u2p）。 */
    const val BUCKET_ID = "u2"

    /**
     * R2 对象 key：Hive 风格分区路径，便于离线分析。
     * 日期用任务创建时间（UTC）。
     */
    fun objectKey(projectId: String, deepResearchId: UUID, createdAt: Instant): String {
        val d = createdAt.atZone(ZoneOffset.UTC)
        return "data/project=%s/type=deep_research/year=%04d/month=%02d/day=%02d/%s.json"
            .format(projectId, d.year, d.monthValue, d.dayOfMonth, deepResearchId)
    }

    /**
     * 组装 doc JSON。[scanRecordSnapshot] 为 AI 回写后语义的完整 scan_record 快照
     * （调用方以 DB 现值 + AI 结果覆盖 basicResult/hasDeepSearch 组装）；[deepResearchSnapshot] 为改完后
     * 的 deep_research 记录信息。premiumResult 为顶级字段（前端只摘此字段）。
     */
    fun buildDoc(
        docVersion: Int,
        promptVersion: String,
        scanRecordSnapshot: Map<String, Any?>,
        deepResearchSnapshot: Map<String, Any?>,
        premiumResult: Map<String, Any?>?,
    ): Map<String, Any?> = linkedMapOf(
        "docVersion" to docVersion,
        "promptVersion" to promptVersion,
        "scanRecordSnapshot" to scanRecordSnapshot,
        "deepResearchSnapshot" to deepResearchSnapshot,
        "premiumResult" to premiumResult,
    )
}

/**
 * 后台 DeepResearch 任务的不可变上下文：mutation 事务内创建记录后、事务提交后传给后台执行。
 * 后台任务脱离 HTTP 请求线程/请求事务/请求 ActionContext，仅依赖此快照（设计 §8.1）。
 */
data class DeepResearchTaskContext(
    val projectId: String,
    val customerId: UUID,
    val deepResearchId: UUID,
    val scanRecordId: UUID,
    val images: List<ImageRefItem>,
    val locale: String?,
    val country: String?,
    val currency: String?,
    val promptVersion: String,
    val docVersion: Int,
    val createdAt: Instant,
) {
    /** images 的轻量载体（避免直接依赖 Jimmer 实体跨线程）。 */
    data class ImageRefItem(val key: String, val category: Int?)
}

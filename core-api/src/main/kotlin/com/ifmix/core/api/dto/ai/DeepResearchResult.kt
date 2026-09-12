package com.ifmix.core.api.dto.ai

import java.util.UUID

/**
 * DeepResearch 的外部结果 — 事务外调用 AI 后返回，再由事务内方法持久化。
 * images 在 AI 调用之前已单独提交，故此处不再携带。
 *
 * 仅当 [isSuccess] 为 true（AI 调用成功且 scan_status.status ∈ {SUCCESS, PARTIAL}）时
 * 才写回 scan 结果；否则返回失败状态与 [scanStatus] 详情要求用户修正。
 */
data class DeepResearchResult(
    val scanRecordId: UUID,
    val projectId: UUID,
    val basicResult: Map<String, Any?>?,
    val premiumResult: Map<String, Any?>?,
    val promptVersion: String,
) {
    /** AI 返回的 basic_result.scan_status（可能为 null）。 */
    @Suppress("UNCHECKED_CAST")
    val scanStatus: Map<String, Any?>?
        get() = basicResult?.get("scan_status") as? Map<String, Any?>

    /** AI 返回的 scan_status.status 枚举字符串（大写），无则 null。 */
    val status: String?
        get() = (scanStatus?.get("status") as? String)?.trim()?.uppercase()

    /**
     * 是否为可用结果。SUCCESS/PARTIAL 视为成功；
     * INSUFFICIENT_IMAGE/NON_PHYSICAL_SUBJECT 及缺失/未知状态视为失败（不写回、要求修正）。
     */
    val isSuccess: Boolean
        get() = status == "SUCCESS" || status == "PARTIAL"
}

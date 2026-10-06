package com.ifmix.core.api.dto.cs

import java.util.UUID

/**
 * 提交反馈协议入参（手写，字段与 schema/customer/cs.graphqls 的 `input SubmitFeedbackInput` 一致）。
 */
data class SubmitFeedbackInput(
    /** 反馈来源主题。10=Scan, 20=DeepResearch, 30=App。 */
    val topic: Int,
    /** 反馈原因（多选）。 */
    val reasons: List<Int>,
    /** 所在页面区块。wire 保留该字段（schema 一致性），服务端与原 CsFetcher 一样不落库。 */
    val section: String? = null,
    /** SPM 埋点位置标识。 */
    val spm: String? = null,
    val comment: String? = null,
    /** 可选联系方式：邮箱（回访用）。 */
    val email: String? = null,
    /** 可选联系方式：手机号（回访用）。 */
    val phone: String? = null,
    val scanRecordId: UUID? = null,
)

/** → 业务层请求体（原 CsFetcher.toReq 语义：section 仅在 wire 上保留，不进 Req）。 */
fun SubmitFeedbackInput.toReq() = SubmitFeedbackReq(
    topic = topic,
    reasons = reasons,
    comment = comment,
    email = email,
    phone = phone,
    scanRecordId = scanRecordId,
    spm = spm,
)

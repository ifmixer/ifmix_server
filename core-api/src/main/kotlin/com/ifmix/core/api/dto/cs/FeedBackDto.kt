package com.ifmix.core.api.dto.cs

import java.util.UUID

/** 提交反馈请求体。身份由 header 推导，客户端不可指定。 */
data class SubmitFeedbackReq(
    /** 反馈来源主题。10=Scan, 20=DeepResearch, 30=App。码表见 FeedbackTopics。 */
    val topic: Int,
    /** 反馈原因（多选）。码表见 FeedbackReasons。 */
    val reasons: List<Int>,
    val comment: String? = null,
    /** 可选联系方式：邮箱（回访用）。 */
    val email: String? = null,
    /** 可选联系方式：手机号（回访用）。 */
    val phone: String? = null,
    val scanRecordId: UUID? = null,
    /** SPM 埋点位置标识 */
    val spm: String? = null,
)

package com.ifmix.core.api.dto.cms

import java.util.UUID

/** 提交反馈请求体。身份由 header 推导，客户端不可指定。 */
data class SubmitFeedbackReq(
    /** 反馈原因（多选）。码表见 FeedbackReasons。 */
    val reasons: List<Int>,
    val comment: String? = null,
    val scanRecordId: UUID? = null,
    /** SPM 埋点位置标识 */
    val spm: String? = null,
)

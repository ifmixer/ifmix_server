package com.ifmix.api.core.dto.cms

import java.util.UUID

/** 提交反馈请求体。身份由 header 推导，客户端不可指定。 */
data class SubmitFeedbackReq(
    /** 反馈分类编码。0=UNKNOWN, 10=LIKED, 20=PRICE_TOO_HIGH, 21=PRICE_TOO_LOW, 22=PRICE_MISSING, 23=PRICE_UNREASONABLE, 30=WRONG_IDENTIFICATION, 40=FEATURE_REQUEST, 41=MORE_RECOMMENDATIONS */
    val category: Int,
    val comment: String? = null,
    val scanRecordId: UUID? = null,
    /** SPM 埋点位置标识 */
    val spm: String? = null,
)

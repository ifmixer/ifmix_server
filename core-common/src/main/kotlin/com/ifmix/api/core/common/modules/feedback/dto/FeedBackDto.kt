package com.ifmix.api.core.common.modules.feedback.dto

import java.util.UUID

/** 提交反馈请求体。身份由 header 推导，客户端不可指定。 */
data class SubmitFeedbackReq(
    /** 反馈分类编码。0=UNKNOWN, 100=LIKED, 200=PRICE_TOO_HIGH, 210=PRICE_TOO_LOW, 220=PRICE_MISSING, 300=WRONG_IDENTIFICATION, 400=FEATURE_REQUEST, 410=MORE_RECOMMENDATIONS */
    val category: Int,
    val comment: String? = null,
    val scanRecordId: UUID? = null,
)

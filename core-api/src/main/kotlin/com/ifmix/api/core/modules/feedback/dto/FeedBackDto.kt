package com.ifmix.api.core.modules.feedback.dto

import com.ifmix.api.core.entity.enums.FeedbackCategory
import java.util.UUID

/** 提交反馈请求体。身份由 header 推导，客户端不可指定。 */
data class SubmitFeedbackReq(
    val category: FeedbackCategory,
    val comment: String? = null,
    val scanRecordId: UUID? = null,
)

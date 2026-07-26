package com.ifmix.api.core.modules.feedback

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** feedback 模块请求/响应 DTO。 */

data class SubmitReq(
    @field:NotNull val category: FeedbackCategory? = null,
    @field:Size(max = 1000) val note: String? = null,
    val scanRecordId: String? = null,
)

data class SubmitRes(val id: String?)

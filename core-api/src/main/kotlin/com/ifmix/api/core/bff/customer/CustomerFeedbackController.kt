package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.feedback.FeedbackService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 反馈分类枚举。 */
enum class FeedbackCategory {
    LIKED,
    PRICE_TOO_HIGH,
    PRICE_TOO_LOW,
    PRICE_MISSING,
    WRONG_IDENTIFICATION,
    FEATURE_REQUEST,
    MORE_RECOMMENDATIONS,
}

/** 提交反馈请求体。身份由 header 推导，客户端不可指定。 */
data class SubmitFeedbackReq(
    val category: FeedbackCategory,
    val comment: String? = null,
    val scanRecordId: String? = null,
)

/** 提交反馈响应：仅返回 ID。 */
data class SubmitFeedbackRes(val id: String)

/** customer BFF 的反馈路由。 */
@RestController
@RequestMapping("/customer/core", produces = ["application/json"])
class CustomerFeedbackController(private val feedbackService: FeedbackService) {

    /** 提交反馈。appId / userId / installId 由 header 推导，客户端不可指定。 */
    @Operation(summary = "提交反馈", description = "scanRecordId 为 ScanDto.id（可选），必须属于当前用户。身份由 header 推导，客户端不可伪造。")
    @PostMapping("/mutation/feedback/submit")
    fun submit(ctx: RequestContext, @Valid @RequestBody req: SubmitFeedbackReq): SubmitFeedbackRes =
        feedbackService.submit(ctx, req)
}

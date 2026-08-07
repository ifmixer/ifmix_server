package com.ifmix.api.core.bff.customer.feedback

import com.ifmix.api.core.infra.dto.CreateOneRes
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.feedback.dto.SubmitFeedbackReq
import com.ifmix.api.core.modules.feedback.service.FeedbackService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** customer BFF 的反馈路由。 */
@RestController
@RequestMapping("/customer")
class CustomerFeedbackController(private val feedbackService: FeedbackService) {

    /** 提交反馈。appId / userId / installId 由 header 推导，客户端不可指定。 */
    @Operation(summary = "提交反馈", description = "scanRecordId 为扫描记录 ID（可选），必须属于当前用户。身份由 header 推导，客户端不可伪造。")
    @PostMapping("/mutation/core/feedback/submitFeedback")
    fun submit(ctx: OperationContext, @Valid @RequestBody req: SubmitFeedbackReq): CreateOneRes =
        feedbackService.submit(ctx, req)
}

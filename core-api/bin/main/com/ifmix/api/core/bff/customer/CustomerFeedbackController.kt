package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.feedback.FeedbackService
import com.ifmix.api.core.modules.feedback.SubmitReq
import com.ifmix.api.core.modules.feedback.SubmitRes
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** customer BFF 的反馈路由。 */
@RestController
@RequestMapping("/customer/core")
class CustomerFeedbackController(private val feedbackService: FeedbackService) {

    /** 提交反馈。 */
    @PostMapping("/mutation/feedback/submit")
    fun submit(ctx: RequestContext, @Valid @RequestBody req: SubmitReq): SubmitRes {
        val id = feedbackService.submit(ctx, req)
        return SubmitRes(id)
    }
}

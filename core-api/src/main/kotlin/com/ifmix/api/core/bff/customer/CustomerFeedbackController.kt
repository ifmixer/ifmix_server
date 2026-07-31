package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.entity.feedback.dto.FeedbackCreateInput
import com.ifmix.api.core.entity.feedback.dto.FeedbackView
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.feedback.FeedbackService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** customer BFF 的反馈路由。 */
@RestController
@RequestMapping("/customer/core")
class CustomerFeedbackController(private val feedbackService: FeedbackService) {

    /** 提交反馈。appId 由 x-app-id 决定，客户端不可指定。 */
    @PostMapping("/mutation/feedback/submit")
    fun submit(ctx: RequestContext, @Valid @RequestBody req: FeedbackCreateInput): FeedbackView =
        feedbackService.submit(ctx, req)
}

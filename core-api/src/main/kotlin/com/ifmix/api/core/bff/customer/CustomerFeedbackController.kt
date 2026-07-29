package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.feedback.FeedbackService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** customer BFF 的反馈路由。 */
@RestController
@RequestMapping("/customer/core")
class CustomerFeedbackController(private val feedbackService: FeedbackService) {

    /** 提交反馈（stub，待与 Jimmer DTO 集成） */
    @PostMapping("/mutation/feedback/submit")
    fun submit(ctx: RequestContext, @RequestBody req: Any): Any = TODO("Not implemented")
}

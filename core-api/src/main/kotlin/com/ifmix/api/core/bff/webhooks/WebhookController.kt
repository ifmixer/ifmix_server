package com.ifmix.api.core.bff.webhooks

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.payment.NotificationDecoder
import com.ifmix.api.core.modules.payment.PaymentFacade
import com.ifmix.api.core.modules.payment.VerifyReq
import com.ifmix.api.core.modules.payment.Platform
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Webhook 控制器：接收各商店的推送通知。
 * 返回 ResponseEntity<String> 以避免 Envelope 包装（商店不需要 JSON envelope）。
 */
@RestController
@RequestMapping("/webhooks/iap")
@ConditionalOnBean(PaymentFacade::class)
class WebhookController(
    private val paymentFacade: PaymentFacade,
    private val appleDecoder: NotificationDecoder,
    private val googleDecoder: NotificationDecoder,
) {

    /** Apple Server Notifications v2 webhook。 */
    @PostMapping("/apple")
    fun handleApple(@RequestBody rawPayload: String): ResponseEntity<String> {
        // TODO: implement proper webhook handling via decoder
        return ResponseEntity.ok("ok")
    }

    /** Google Play 通知 webhook。 */
    @PostMapping("/google")
    fun handleGoogle(@RequestBody rawPayload: String): ResponseEntity<String> {
        // TODO: implement proper webhook handling via decoder
        return ResponseEntity.ok("ok")
    }
}

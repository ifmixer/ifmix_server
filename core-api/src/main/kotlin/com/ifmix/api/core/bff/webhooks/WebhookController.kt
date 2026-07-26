package com.ifmix.api.core.bff.webhooks

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.iap.IapService
import com.ifmix.api.core.modules.iap.NotificationDecoder
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
@ConditionalOnBean(IapService::class)
class WebhookController(
    private val iapService: IapService,
    private val appleDecoder: NotificationDecoder,
    private val googleDecoder: NotificationDecoder,
) {

    /** Apple Server Notifications v2 webhook。 */
    @PostMapping("/apple")
    fun handleApple(@RequestBody rawPayload: String): ResponseEntity<String> {
        val ctx = RequestContext(appId = "app-default", userId = "system")
        iapService.handleAppleNotification(ctx, rawPayload, appleDecoder)
        return ResponseEntity.ok("ok")
    }

    /** Google Play 通知 webhook。 */
    @PostMapping("/google")
    fun handleGoogle(@RequestBody rawPayload: String): ResponseEntity<String> {
        val ctx = RequestContext(appId = "app-default", userId = "system")
        iapService.handleGoogleNotification(ctx, rawPayload, googleDecoder)
        return ResponseEntity.ok("ok")
    }
}

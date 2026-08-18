package com.ifmix.api.core.modules.iap.service

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.iap.NotificationDecoder
import com.ifmix.api.core.modules.iap.VerifyReq
import com.ifmix.api.core.modules.iap.VerifyRes
import org.springframework.stereotype.Service

@Service
class IapFacadeService(
    private val commands: IapCommands,
    private val webhookHandler: IapWebhookHandler,
) {
    fun verifyPurchase(ctx: OperationContext, req: VerifyReq): VerifyRes = commands.verifyPurchase(ctx, req)
    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        webhookHandler.handleAppleNotification(ctx, rawPayload, decoder)
    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        webhookHandler.handleGoogleNotification(ctx, rawPayload, decoder)
}

package com.ifmix.api.core.modules.payment

import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.tx.TxRunner
import com.ifmix.api.core.modules.payment.handler.PaymentHandler
import com.ifmix.api.core.modules.payment.handler.PaymentWebhookHandler
import com.ifmix.api.core.dto.payment.VerifyReq
import com.ifmix.api.core.dto.payment.VerifyRes
import org.springframework.stereotype.Service

@Service
class PaymentFacade(
    private val svcCtxFactory: SvcCtxFactory,
    private val commands: PaymentHandler,
    private val webhookHandler: PaymentWebhookHandler,
    private val tx: TxRunner,
) {
    fun verifyIapPurchase(ctx: OperationContext, req: VerifyReq): VerifyRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> commands.verifyIapPurchase(sc, req) }
    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> webhookHandler.handleAppleNotification(sc, rawPayload, decoder) }
    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> webhookHandler.handleGoogleNotification(sc, rawPayload, decoder) }
}

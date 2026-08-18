package com.ifmix.api.core.modules.payment.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.modules.payment.NotificationDecoder
import com.ifmix.api.core.dto.iap.VerifyReq
import com.ifmix.api.core.dto.iap.VerifyRes
import org.springframework.stereotype.Service

@Service
class PaymentFacadeService(
    private val commands: PaymentInternalService,
    private val webhookHandler: PaymentWebhookHandler,
    private val tx: TxRunner,
) {
    private fun svc(ctx: OperationContext) = SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)

    fun verifyPurchase(ctx: OperationContext, req: VerifyReq): VerifyRes =
        tx.withTx(svc(ctx)) { sc -> commands.verifyPurchase(sc, req) }
    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        tx.withTx(svc(ctx)) { sc -> webhookHandler.handleAppleNotification(sc, rawPayload, decoder) }
    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        tx.withTx(svc(ctx)) { sc -> webhookHandler.handleGoogleNotification(sc, rawPayload, decoder) }
}

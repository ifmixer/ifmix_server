package com.ifmix.api.core.modules.payment

import com.ifmix.api.core.dto.payment.VerifyReq
import com.ifmix.api.core.dto.payment.VerifyRes
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.payment.handler.PaymentAggHandler
import com.ifmix.api.core.modules.payment.handler.PaymentWebhookHandler
import org.springframework.stereotype.Service

@Service
class PaymentFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: PaymentAggHandler,
    private val webhookHandler: PaymentWebhookHandler,
) {
    /**
     * verifyIapPurchase: 外部商店验证在事务外，DB upsert 也在事务外（单条写入无复杂事务需求）。
     */
    fun verifyIapPurchase(ctx: OperationContext, req: VerifyReq): VerifyRes {
        val verifyResult = handler.verifyPurchase(req)
        return handler.verifyAndUpsert(mcFactory.forApp(ctx), req, verifyResult)
    }

    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        webhookHandler.handleAppleNotification(mcFactory.forApp(ctx), rawPayload, decoder)

    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        webhookHandler.handleGoogleNotification(mcFactory.forApp(ctx), rawPayload, decoder)
}

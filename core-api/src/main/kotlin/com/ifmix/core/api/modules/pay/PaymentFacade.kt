package com.ifmix.core.api.modules.pay

import com.ifmix.core.api.dto.payment.VerifyReq
import com.ifmix.core.api.dto.payment.VerifyRes
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.pay.handler.PaymentAggHandler
import com.ifmix.core.api.modules.pay.handler.PaymentWebhookHandler
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
        return handler.verifyAndUpsert(mcFactory.forProject(ctx), req, verifyResult)
    }

    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        webhookHandler.handleAppleNotification(mcFactory.forProject(ctx), rawPayload, decoder)

    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        webhookHandler.handleGoogleNotification(mcFactory.forProject(ctx), rawPayload, decoder)
}

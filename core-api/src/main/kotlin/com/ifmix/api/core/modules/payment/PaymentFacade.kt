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
    private val handler: PaymentHandler,
    private val webhookHandler: PaymentWebhookHandler,
    private val tx: TxRunner,
) {
    /**
     * verifyIapPurchase: 外部商店验证在事务外，DB upsert 在事务内。
     * 避免网络 IO 占住事务连接。
     */
    fun verifyIapPurchase(ctx: OperationContext, req: VerifyReq): VerifyRes {
        // Step 1: 外部验证（无事务）
        val verifyResult = handler.verifyPurchase(req)
        // Step 2: DB 写入（有事务）
        return tx.withTx(svcCtxFactory.forApp(ctx)) { sc ->
            handler.verifyAndUpsert(sc, req, verifyResult)
        }
    }

    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> webhookHandler.handleAppleNotification(sc, rawPayload, decoder) }
    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> webhookHandler.handleGoogleNotification(sc, rawPayload, decoder) }
}

package com.ifmix.core.api.bff.api.customer.pay

import com.ifmix.core.api.bff.api.customer.demo.DemoController
import com.ifmix.core.api.dto.payment.VerifyIapPurchaseRes
import com.ifmix.core.api.dto.payment.VerifyReq
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.modules.pay.PaymentFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * pay 模块 API controller：1 个 `POST /api/customer/core/{actionName}`。
 *
 * 模式与 [DemoController] 一致：[ActionContextFactory.fromRpc] 构造 ctx → 直调 [PaymentFacade]。
 * 原 PaymentFetcher 未包 GlobalTxRunner（验签在事务外、单条 upsert 亦无聚合事务）——此处同样不包。
 * 入参直接复用业务层手写 [VerifyReq]（与 schema `input verifyIapPurchaseInput` 字段级一致：
 * platform/productId 必填，signedTransaction/purchaseToken 可空）。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Pay API", description = "pay 模块 API（GraphQL 去化 M1）")
class PayController(
    private val ctxFactory: ActionContextFactory,
    private val paymentService: PaymentFacade,
) {

    companion object {
        // pay 模块 action 常量（原 PaySpecs 机械搬移；CUSTOMER + requireProjectId=true，
        // 对照原 PaymentFetcher `fromDfe(dfe)` 全默认实参）。
        const val IAP_VERIFY = "m_pay_iap_verify"
    }

    @Operation(operationId = IAP_VERIFY)
    @PostMapping(IAP_VERIFY, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun verifyIapPurchase(request: HttpServletRequest, @RequestBody body: ApiRequestBody<VerifyReq>): ResponseEntity<Envelope<VerifyIapPurchaseRes>> {
        val ctx = ctxFactory.fromRpc(request, IAP_VERIFY, isMutation = true, body = body)
        val req = body.requireInput()
        val res = paymentService.verifyIapPurchase(ctx, req)
        return ResponseEntity.ok(
            Envelope.ok(
                VerifyIapPurchaseRes(
                    tier = res.tier,
                    // 原 fetcher：epoch millis → Instant → GraphQL DateTime（ISO-8601）
                    expiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it).toString() },
                ),
            ).copy(reqId = ctx.requestId),
        )
    }
}

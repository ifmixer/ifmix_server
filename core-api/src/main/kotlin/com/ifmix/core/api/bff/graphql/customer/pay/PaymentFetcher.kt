package com.ifmix.core.api.bff.graphql.customer.pay

import com.ifmix.core.api.generated.types.verifyIapPurchaseInput
import com.ifmix.core.api.generated.types.VerifyIapPurchaseResult
import com.ifmix.core.api.infra.graphql.OperationContextProvider
import com.ifmix.core.api.dto.payment.VerifyReq
import com.ifmix.core.api.modules.pay.PaymentFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import java.time.Instant

@DgsComponent
class PaymentFetcher(
    private val paymentService: PaymentFacade,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "m_pay_verifyIapPurchase")
    fun verifyIapPurchase(dfe: DgsDataFetchingEnvironment, @InputArgument input: verifyIapPurchaseInput): VerifyIapPurchaseResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val res = paymentService.verifyIapPurchase(ctx, VerifyReq(
            platform = input.platform,
            signedTransaction = input.signedTransaction,
            purchaseToken = input.purchaseToken,
            productId = input.productId,
        ))
        return VerifyIapPurchaseResult(
            tier = res.tier,
            expiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it) },
        )
    }
}

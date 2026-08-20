package com.ifmix.api.core.bff.graphql.customer.payment

import com.ifmix.api.core.generated.types.verifyIapPurchaseInput
import com.ifmix.api.core.generated.types.verifyIapPurchasePayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.dto.payment.VerifyReq
import com.ifmix.api.core.modules.payment.PaymentFacade
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

    @DgsMutation(field = "mutation_payment_verifyIapPurchase")
    fun verifyIapPurchase(dfe: DgsDataFetchingEnvironment, @InputArgument input: verifyIapPurchaseInput): verifyIapPurchasePayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val res = paymentService.verifyIapPurchase(ctx, VerifyReq(
            platform = input.platform,
            signedTransaction = input.signedTransaction,
            purchaseToken = input.purchaseToken,
            productId = input.productId,
        ))
        return verifyIapPurchasePayload(
            tier = res.tier,
            expiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it) },
        )
    }
}

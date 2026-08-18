package com.ifmix.api.core.bff.graphql.customer.iap

import com.ifmix.api.core.generated.types.VerifyPurchaseInput
import com.ifmix.api.core.generated.types.VerifyPurchasePayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.dto.iap.VerifyReq
import com.ifmix.api.core.modules.payment.service.PaymentFacadeService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import java.time.Instant

@DgsComponent
class IapFetcher(
    private val iapService: PaymentFacadeService,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "mutation_iap_verifyPurchase")
    fun verifyPurchase(dfe: DgsDataFetchingEnvironment, @InputArgument input: VerifyPurchaseInput): VerifyPurchasePayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val res = iapService.verifyPurchase(ctx, VerifyReq(
            platform = input.platform,
            signedTransaction = input.signedTransaction,
            purchaseToken = input.purchaseToken,
            productId = input.productId,
        ))
        return VerifyPurchasePayload(
            tier = res.tier,
            expiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it) },
        )
    }
}

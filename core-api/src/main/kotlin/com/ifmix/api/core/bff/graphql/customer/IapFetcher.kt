package com.ifmix.api.core.bff.graphql.customer

import com.ifmix.api.core.generated.types.VerifyPurchaseInput
import com.ifmix.api.core.generated.types.VerifyPurchasePayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.iap.VerifyReq
import com.ifmix.api.core.modules.iap.service.IapService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import java.time.Instant

@DgsComponent
class IapFetcher(
    private val iapService: IapService,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "mutation_iap_verifyPurchase")
    fun verifyPurchase(dfe: DgsDataFetchingEnvironment, @InputArgument input: VerifyPurchaseInput): VerifyPurchasePayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val platformCode = when (input.platform.name) {
            "APPLE" -> 100
            "GOOGLE" -> 200
            else -> 100
        }
        val res = iapService.verifyPurchase(ctx, VerifyReq(
            platform = platformCode,
            signedTransaction = input.signedTransaction,
            purchaseToken = input.purchaseToken,
            productId = input.productId,
        ))
        return VerifyPurchasePayload(
            tier = res.tier.name,
            expiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it) },
        )
    }
}

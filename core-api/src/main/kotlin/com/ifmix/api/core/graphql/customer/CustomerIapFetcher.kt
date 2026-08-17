package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.iap.IapService
import com.ifmix.api.core.modules.iap.Platform
import com.ifmix.api.core.modules.iap.VerifyReq
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import java.time.Instant

/**
 * GraphQL IAP fetcher — customer BFF。
 * 将 Schema input 映射为 IapService 的 VerifyReq，再将响应展平为 VerifyPurchaseResult。
 */
@DgsComponent
class CustomerIapFetcher(private val iapService: IapService) {

    @DgsMutation(field = "iap_verifyPurchase")
    fun verifyPurchase(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): VerifyPurchaseResult {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        val platform = input["platform"] as String
        val purchaseToken = input["purchaseToken"] as? String
        val productId = input["productId"] as String
        val platformEnum = Platform.valueOf(platform.uppercase())
        val req = VerifyReq(
            platform = platformEnum,
            purchaseToken = purchaseToken ?: "",
            productId = productId,
        )
        val res = iapService.verifyPurchase(ctx, req)

        return VerifyPurchaseResult(
            expiresAt = null,
            state = res.state.name,
            productId = res.productId,
            tier = res.tier?.name,
        )
    }
}

/** GraphQL 响应类型：verifyPurchase 的结果。 */
data class VerifyPurchaseResult(
    val expiresAt: Long?,
    val state: String,
    val productId: String,
    val tier: String?,
)

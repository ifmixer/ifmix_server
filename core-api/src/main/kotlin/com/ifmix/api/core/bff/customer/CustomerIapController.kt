package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.iap.service.IapService
import com.ifmix.api.core.modules.iap.dto.VerifyReq
import com.ifmix.api.core.modules.iap.dto.VerifyRes
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*

/**
 * customer BFF 的 IAP 路由。
 * 仅在 IapService bean 存在时加载。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(IapService::class)
class CustomerIapController(private val iapService: IapService) {

    /** 验证购买：客户端提交购买凭证，服务端调用商店 API 验证并写入订阅记录。 */
    @Operation(
        summary = "验证 IAP 购买",
        description = """
            客户端提交商店购买凭证，服务端验证并写入订阅。
            - platform=APPLE 时必传 signedTransaction（StoreKit 2 JWS）
            - platform=GOOGLE 时必传 purchaseToken
            - productId 必传（App Store / Google Play 的 SKU）
            
            platform 枚举是商店维度（APPLE/GOOGLE），与 header x-client-platform（设备维度 ios/android/web）是不同概念。
        """,
    )
    @PostMapping("/mutation/iap/verify")
    fun verify(
        ctx: OperationContext,
        @Valid @RequestBody req: VerifyReq,
    ): VerifyRes {
        return iapService.verifyPurchase(ctx, req)
    }
}

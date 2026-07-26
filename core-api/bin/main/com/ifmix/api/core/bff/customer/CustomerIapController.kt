package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.iap.IapService
import com.ifmix.api.core.modules.iap.VerifyReq
import com.ifmix.api.core.modules.iap.VerifyRes
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
    @PostMapping("/mutation/iap/verify")
    fun verify(
        ctx: RequestContext,
        @Valid @RequestBody req: VerifyReq,
    ): VerifyRes {
        return iapService.verifyPurchase(ctx, req)
    }
}

package com.ifmix.api.core.modules.pay

import com.ifmix.api.core.dto.payment.SubStatus
import com.ifmix.api.core.infra.db.UuidV7

/**
 * 购买验证接缝：各商店（Apple / Google）的 verifyIapPurchase 实现不同，
 * 通过此接口解耦。
 */
interface PurchaseVerifier {
    /**
     * 验证一笔购买收据/令牌。
     * @return 验证结果，包含原始交易 ID、产品 SKU、过期时间等
     */
    fun verify(input: VerifyInput): VerifyResult
}

/** 传入 [PurchaseVerifier] 的输入。 */
data class VerifyInput(
    val platform: Int,
    val purchaseToken: String,
    val productId: String,
    val appId: String,
)

/** 验证结果。 */
data class VerifyResult(
    val originalTransactionId: String?,
    val productId: String,
    val expiryDate: java.time.Instant?,
    val subStatus: SubStatus,
    val platform: Int,
)

/**
 * 占位实现：返回一条"已验证"的结果，不真正调用商店 API。
 * 用于开发和集成测试。
 */
class StubPurchaseVerifier : PurchaseVerifier {
    override fun verify(input: VerifyInput): VerifyResult {
        return VerifyResult(
            originalTransactionId = "stub-txn-${UuidV7.generate()}",
            productId = input.productId,
            expiryDate = java.time.Instant.now().plusSeconds(86400L * 30), // 30 days
            subStatus = SubStatus.ACCEPTED,
            platform = input.platform,
        )
    }
}

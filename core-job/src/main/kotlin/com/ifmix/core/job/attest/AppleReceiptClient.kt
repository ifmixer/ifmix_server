package com.ifmix.core.job.attest

/**
 * Apple receipt 交换客户端（规格 §5.8「iOS receipt 回填」）：
 * `POST https://api-appattest.apple.com/v1/attestations`（`{key, attestation}`）。
 *
 * 当前核实的模型不要求 DeviceCheck JWT（§5.6 / §10.4：实现时如核实需要，复用同一套 ES256 JWT 逻辑）；
 * 抽成接口 + [AppleReceiptClientImpl]（JDK HttpClient）供单测以 Fake 替代网络。
 */
interface AppleReceiptClient {

    sealed interface Result {
        data class Success(val receipt: ByteArray) : Result
        /** Apple 侧 attestation 一次性消费：「已使用」类 4xx（400/409 且 body 提示 already-used / CONSUMED）。 */
        object AlreadyUsed : Result
        /** 网络错误 / 5xx / 429：指数退避后下轮重试（保留 attestation_object）。 */
        data class TransientError(val status: Int?, val message: String) : Result
    }

    fun exchangeReceipt(keyId: String, attestationObjectBase64: String): Result
}

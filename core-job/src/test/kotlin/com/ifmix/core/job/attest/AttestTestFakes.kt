package com.ifmix.core.job.attest

/**
 * WP-E 单测用的 Apple / DeviceCheck 客户端替身（规格 §7：HTTP 用可注入 fake，不碰真网络）。
 */
class FakeAppleReceiptClient(
    private var responseFor: (keyId: String) -> AppleReceiptClient.Result = {
        AppleReceiptClient.Result.Success("APPLE_RECEIPT".toByteArray())
    },
) : AppleReceiptClient {
    val calls = mutableListOf<Pair<String, String>>()

    override fun exchangeReceipt(keyId: String, attestationObjectBase64: String): AppleReceiptClient.Result {
        calls += keyId to attestationObjectBase64
        return responseFor(keyId)
    }
}

class FakeDeviceCheckClient(
    private var result: DeviceCheckClient.CallResult =
        DeviceCheckClient.CallResult.Success(DeviceCheckClient.Bits(bit0 = 1, bit1 = 0)),
) : DeviceCheckClient {
    val calls = mutableListOf<Pair<String, ByteArray>>()

    override fun refresh(ios: AppAttestIosConfig, receiptBytes: ByteArray): DeviceCheckClient.CallResult {
        calls += ios.deviceCheckKeyId.orEmpty() to receiptBytes
        return result
    }
}

/** 测试里共用的默认任务参数（退避 base=1h / cap=24h / 刷新间隔 24h / evidence 保留 90d）。 */
fun testAttestJobConfig() = AttestJobConfig(
    batchSize = 200,
    backoffBaseHours = 1,
    backoffCapHours = 24,
    refreshIntervalHours = 24,
    evidenceRetainDays = 90,
)

/** 造一把可被 DeviceCheckClientImpl.parseP8 解析的 EC P-256 .p8 私钥（测试/冒烟用）。 */
fun generateTestP8KeyPem(): String {
    val gen = java.security.KeyPairGenerator.getInstance("EC")
    gen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    val p8 = gen.generateKeyPair().private.encoded ?: error("no encoded key")
    val der = java.util.Base64.getEncoder().encodeToString(p8)
    return "-----BEGIN PRIVATE KEY-----\n" +
        der.chunked(64).joinToString("\n") +
        "\n-----END PRIVATE KEY-----"
}

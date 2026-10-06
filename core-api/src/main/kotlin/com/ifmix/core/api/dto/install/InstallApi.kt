package com.ifmix.core.api.dto.install

/** install 模块 API 入参/出参（wire 契约，字段与 GraphQL input/result 一一对应；proof/deviceInfo 为自由结构 JSON）。 */
data class CreateInstallInput(
    val deviceInfo: Map<String, Any?>? = null,
    val proof: Map<String, Any?>? = null,
    val proofStatus: Int? = null,
    val storeType: Int? = null,
)

data class UpdateInstallInput(
    val firebaseInstallId: String? = null,
    val fcmToken: String? = null,
    val scanResultNotiEnabled: Boolean? = null,
    val deepResearchNotiEnabled: Boolean? = null,
    val deviceInfo: Map<String, Any?>? = null,
)

data class RecoverInstallInput(val keyId: String, val assertion: String, val challenge: String)

data class AttestExistingInput(val proof: Map<String, Any?>)

data class CreateInstallRes(val installId: java.util.UUID, val installToken: String, val attestationStatus: Int)

data class AttestChallengeRes(val enabled: Boolean, val challenge: String?, val expiresInSec: Int)

data class AttestExistingRes(val attestationStatus: Int)

data class UpdateInstallRes(val success: Boolean)

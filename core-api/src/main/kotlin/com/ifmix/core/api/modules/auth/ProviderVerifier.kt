package com.ifmix.core.api.modules.auth

import com.ifmix.core.api.entity.auth.Idp
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * 第三方 provider 的 credential 验证器。
 * 每种 idpType 注册一个实现，通过 Idp.config JSONB 获取验证所需配置。
 */
interface ProviderVerifier {

    data class VerifiedResult(
        val accountId: String,
        val email: String?,
        val emailVerified: Boolean,
        val phone: String?,
        val userMetadata: Map<String, Any?>?,
    )

    fun verifyWithIdpConfig(idp: Idp, platform: ClientPlatform?, credential: String): VerifiedResult
}

private fun decodeOrFail(decoder: JwtDecoder, idToken: String): Jwt = try {
    decoder.decode(idToken)
} catch (e: Exception) {
    throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, e.message ?: "provider verify failed")
}

private fun Jwt.toVerifiedResult(): ProviderVerifier.VerifiedResult = ProviderVerifier.VerifiedResult(
    accountId = subject ?: "",
    email = getClaimAsString("email"),
    emailVerified = (getClaim("email_verified") as? Boolean) ?: false,
    phone = getClaimAsString("phone_number"),
    userMetadata = mapOf("name" to getClaimAsString("name"), "picture" to getClaimAsString("picture")),
)

private fun requireAud(jwt: Jwt, expected: String?) {
    if (expected.isNullOrBlank() || jwt.audience?.contains(expected) != true) {
        throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "aud mismatch")
    }
}

class GoogleVerifier(private val decoder: JwtDecoder) : ProviderVerifier {
    override fun verifyWithIdpConfig(idp: Idp, platform: ClientPlatform?, credential: String): ProviderVerifier.VerifiedResult {
        val jwt = decodeOrFail(decoder, credential)
        val config = idp.config
        @Suppress("UNCHECKED_CAST")
        val clientIds = config["clientIds"] as? Map<String, String> ?: emptyMap()
        val aud = when (platform) {
            ClientPlatform.IOS -> clientIds["ios"]
            ClientPlatform.ANDROID -> clientIds["android"]
            ClientPlatform.WEB, null -> clientIds["web"]
        }
        requireAud(jwt, aud)
        return jwt.toVerifiedResult()
    }
}

class AppleVerifier(private val decoder: JwtDecoder) : ProviderVerifier {
    override fun verifyWithIdpConfig(idp: Idp, platform: ClientPlatform?, credential: String): ProviderVerifier.VerifiedResult {
        val jwt = decodeOrFail(decoder, credential)
        val config = idp.config
        val aud = if (platform == ClientPlatform.WEB) config["servicesId"] as? String else config["bundleId"] as? String
        requireAud(jwt, aud)
        return jwt.toVerifiedResult()
    }
}

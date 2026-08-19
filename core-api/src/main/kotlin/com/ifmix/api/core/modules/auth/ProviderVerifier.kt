package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ClientPlatform
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.modules.app.AppConfigView
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * 第三方 provider 的 id_token 验证器。
 *
 * Google / Apple 各自有自己的 JWKS endpoint，
 * 通过 spring-security-oauth2-jose 的 NimbusJwtDecoder 自动验证签名 + claims。
 */
data class VerifiedProvider(
    val accountId: String,
    val email: String?,
    val emailVerified: Boolean,
    val phone: String?,
    val userMetadata: Map<String, Any?>,
)

interface ProviderVerifier {
    val provider: String
    fun verify(config: AppConfigView, platform: ClientPlatform?, idToken: String): VerifiedProvider
}

private fun decodeOrFail(decoder: JwtDecoder, idToken: String): Jwt = try {
    decoder.decode(idToken)
} catch (e: Exception) {
    throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, e.message ?: "provider verify failed")
}

private fun Jwt.toVerified(): VerifiedProvider = VerifiedProvider(
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
    override val provider = "google"
    override fun verify(config: AppConfigView, platform: ClientPlatform?, idToken: String): VerifiedProvider {
        val jwt = decodeOrFail(decoder, idToken)
        val aud = when (platform) {
            ClientPlatform.IOS -> config.google.clientIds.ios
            ClientPlatform.ANDROID -> config.google.clientIds.android
            ClientPlatform.WEB, null -> config.google.clientIds.web
        }
        requireAud(jwt, aud)
        return jwt.toVerified()
    }
}

class AppleVerifier(private val decoder: JwtDecoder) : ProviderVerifier {
    override val provider = "apple"
    override fun verify(config: AppConfigView, platform: ClientPlatform?, idToken: String): VerifiedProvider {
        val jwt = decodeOrFail(decoder, idToken)
        val aud = if (platform == ClientPlatform.WEB) config.apple.servicesId else config.appleBundleId
        requireAud(jwt, aud)
        return jwt.toVerified()
    }
}

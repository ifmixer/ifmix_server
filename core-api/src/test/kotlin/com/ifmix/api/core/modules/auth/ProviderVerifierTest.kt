package com.ifmix.core.api.modules.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.ifmix.core.api.entity.auth.Idp
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Instant

/** 构造 Google IDP：config.clientIds = {ios,android,web} */
private fun googleIdp() = Idp {
    providerType = 20
    name = "google"
    thirdId = "google"
    desc = null
    config = mapOf("clientIds" to mapOf("ios" to "gid-ios", "android" to "gid-and", "web" to "gid-web"))
}

/** 构造 Apple IDP：config.bundleId（native）/ servicesId（web） */
private fun appleIdp() = Idp {
    providerType = 10
    name = "apple"
    thirdId = "apple"
    desc = null
    config = mapOf("bundleId" to "com.x.app", "servicesId" to "com.x.svc")
}

private fun jwt(aud: String, sub: String = "sub123") = Jwt.withTokenValue("t")
    .header("alg", "RS256").subject(sub).audience(listOf(aud))
    .claim("email", "a@b.com").claim("email_verified", true).claim("name", "A B")
    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()

class ProviderVerifierTest {
    @Test fun `google verify ok when aud matches platform client id`() {
        val decoder = JwtDecoder { jwt("gid-ios") }
        val v = GoogleVerifier(decoder).verifyWithIdpConfig(googleIdp(), ClientPlatform.IOS, "idtoken")
        assertThat(v.accountId).isEqualTo("sub123")
        assertThat(v.email).isEqualTo("a@b.com")
        assertThat(v.emailVerified).isTrue()
    }

    @Test fun `google verify fails when aud mismatches`() {
        val decoder = JwtDecoder { jwt("wrong-aud") }
        assertThat(
            assertThrows<ApiError> { GoogleVerifier(decoder).verifyWithIdpConfig(googleIdp(), ClientPlatform.IOS, "idtoken") }
        ).isInstanceOf(ApiError::class)
    }

    @Test fun `apple verify uses bundleId for native`() {
        val decoder = JwtDecoder { jwt("com.x.app") }
        val v = AppleVerifier(decoder).verifyWithIdpConfig(appleIdp(), ClientPlatform.IOS, "idtoken")
        assertThat(v.accountId).isEqualTo("sub123")
    }

    @Test fun `apple verify uses servicesId for web`() {
        val decoder = JwtDecoder { jwt("com.x.svc") }
        val v = AppleVerifier(decoder).verifyWithIdpConfig(appleIdp(), ClientPlatform.WEB, "idtoken")
        assertThat(v.accountId).isEqualTo("sub123")
    }

    @Test fun `verify fails when decoder throws`() {
        val decoder = JwtDecoder { throw org.springframework.security.oauth2.jwt.JwtException("bad") }
        assertThrows<ApiError> { GoogleVerifier(decoder).verifyWithIdpConfig(googleIdp(), ClientPlatform.IOS, "idtoken") }
    }
}

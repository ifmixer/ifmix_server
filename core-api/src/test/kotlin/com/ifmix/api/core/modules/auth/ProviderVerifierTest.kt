package com.ifmix.api.core.modules.auth

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.ifmix.api.core.entity.appconfig.AppleConfigValue
import com.ifmix.api.core.entity.appconfig.ConfigContent
import com.ifmix.api.core.entity.appconfig.GoogleConfigValue
import com.ifmix.api.core.entity.appconfig.GoogleClientIdsValue
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ClientPlatform
import com.ifmix.api.core.model.AppConfigRevision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Instant
import java.util.UUID

private val mapper = jacksonObjectMapper()

private fun cfg() = AppConfigRevision(
    id = UUID.randomUUID(),
    appId = UUID.randomUUID(),
    authTenantId = UUID.randomUUID(),
    appleBundleId = "com.x.app",
    androidPackageName = null,
    revisionNumber = 1,
    enabled = true,
    slug = "test",
    note = "test",
    createdAt = Instant.now(),
    content = mapper.writeValueAsString(
        ConfigContent(
            apple = AppleConfigValue(servicesId = "com.x.svc"),
            google = GoogleConfigValue(clientIds = GoogleClientIdsValue(ios = "gid-ios", android = "gid-and", web = "gid-web")),
        )
    ),
)

private fun jwt(aud: String, sub: String = "sub123") = Jwt.withTokenValue("t")
    .header("alg", "RS256").subject(sub).audience(listOf(aud))
    .claim("email", "a@b.com").claim("email_verified", true).claim("name", "A B")
    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()

class ProviderVerifierTest {
    @Test fun `google verify ok when aud matches platform client id`() {
        val decoder = JwtDecoder { jwt("gid-ios") }
        val v = GoogleVerifier(decoder).verify(cfg(), ClientPlatform.IOS, "idtoken")
        assertThat(v.accountId).isEqualTo("sub123")
        assertThat(v.email).isEqualTo("a@b.com")
        assertThat(v.emailVerified).isTrue()
    }

    @Test fun `google verify fails when aud mismatches`() {
        val decoder = JwtDecoder { jwt("wrong-aud") }
        assertThatThrownBy { GoogleVerifier(decoder).verify(cfg(), ClientPlatform.IOS, "idtoken") }
            .isInstanceOf(ApiError::class.java)
    }

    @Test fun `apple verify uses bundleId for native`() {
        val decoder = JwtDecoder { jwt("com.x.app") }
        val v = AppleVerifier(decoder).verify(cfg(), ClientPlatform.IOS, "idtoken")
        assertThat(v.accountId).isEqualTo("sub123")
    }

    @Test fun `apple verify uses servicesId for web`() {
        val decoder = JwtDecoder { jwt("com.x.svc") }
        val v = AppleVerifier(decoder).verify(cfg(), ClientPlatform.WEB, "idtoken")
        assertThat(v.accountId).isEqualTo("sub123")
    }

    @Test fun `verify fails when decoder throws`() {
        val decoder = JwtDecoder { throw org.springframework.security.oauth2.jwt.JwtException("bad") }
        assertThatThrownBy { GoogleVerifier(decoder).verify(cfg(), ClientPlatform.IOS, "idtoken") }
            .isInstanceOf(ApiError::class.java)
    }
}

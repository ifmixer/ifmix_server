package com.ifmix.api.core.infra.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthJwtServiceTest {
    private val svc = AuthJwtService(AuthJwtKeys(null), "test-issuer", 900)

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val installId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val appId = "00000000-0000-0000-0000-000000000099"

    @Test fun `sign then verify returns userId and installId`() {
        val token = svc.signAccess(appUserId = userId.toString(), installId = installId.toString(), appId = appId)
        val result = svc.verify(token)
        assertThat(result).isNotNull
        assertThat(result!!.userId).isEqualTo(userId.toString())
        assertThat(result.installId).isEqualTo(installId.toString())
        assertThat(result.appId).isEqualTo(appId)
        assertThat(result.isUser).isTrue()
    }

    @Test fun `verify returns null for garbage token`() {
        assertThat(svc.verify("not.a.jwt")).isNull()
    }

    @Test fun `verify returns null when expired`() {
        val shortSvc = AuthJwtService(AuthJwtKeys(null), "test-issuer", -1)
        val token = shortSvc.signAccess(appUserId = userId.toString(), installId = installId.toString(), appId = appId)
        assertThat(shortSvc.verify(token)).isNull()
    }

    @Test fun `signInstall produces install-type token`() {
        val token = svc.signInstall(installId = installId.toString(), appId = appId)
        val result = svc.verify(token)
        assertThat(result).isNotNull
        assertThat(result!!.userId).isNull()
        assertThat(result.installId).isEqualTo(installId.toString())
        assertThat(result.appId).isEqualTo(appId)
        assertThat(result.isInstall).isTrue()
    }

    @Test fun `jwkSet contains public key and no private d`() {
        val json = svc.jwkSetJson()
        assertThat(json).contains("\"kty\":\"OKP\"").contains("\"crv\":\"Ed25519\"")
        assertThat(json).doesNotContain("\"d\":")
    }
}

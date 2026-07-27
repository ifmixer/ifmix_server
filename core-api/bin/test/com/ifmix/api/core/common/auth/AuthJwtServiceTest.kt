package com.ifmix.api.core.common.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthJwtServiceTest {
    private val svc = AuthJwtService(AuthJwtKeys(null), "test-issuer", 900)

    @Test fun `sign then verify returns appUserId when aid matches`() {
        val token = svc.signAccess(appUserId = "u1", appId = "app1")
        assertThat(svc.verifyAccess(token, expectedAppId = "app1")).isEqualTo("u1")
    }

    @Test fun `verify returns null when aid mismatches`() {
        val token = svc.signAccess(appUserId = "u1", appId = "app1")
        assertThat(svc.verifyAccess(token, expectedAppId = "other")).isNull()
    }

    @Test fun `verify returns null for garbage token`() {
        assertThat(svc.verifyAccess("not.a.jwt", "app1")).isNull()
    }

    @Test fun `verify returns null when expired`() {
        val shortSvc = AuthJwtService(AuthJwtKeys(null), "test-issuer", -1)
        val token = shortSvc.signAccess(appUserId = "u1", appId = "app1")
        assertThat(shortSvc.verifyAccess(token, "app1")).isNull()
    }

    @Test fun `jwkSet contains public key and no private d`() {
        val json = svc.jwkSetJson()
        assertThat(json).contains("\"kty\":\"OKP\"").contains("\"crv\":\"Ed25519\"")
        assertThat(json).doesNotContain("\"d\":")
    }
}

package com.ifmix.core.api.infra.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class AuthJwtServiceTest {
    private val svc = AuthJwtService(AuthJwtKeys(null), "test-issuer", 900)

    private val customerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val projectId = "00000000-0000-0000-0000-000000000099"

    @Test fun `sign then verify returns actorId actorType and anonymous`() {
        val token = svc.signAccess(
            actorId = customerId.toString(),
            actorType = AuthJwtService.ACTOR_CUSTOMER,
            projectId = projectId,
            anonymous = true,
        )
        val result = svc.verify(token)
        assertThat(result).isNotNull
        assertThat(result!!.actorId).isEqualTo(customerId.toString())
        assertThat(result.actorType).isEqualTo(AuthJwtService.ACTOR_CUSTOMER)
        assertThat(result.projectId).isEqualTo(projectId)
        assertThat(result.isCustomer).isTrue()
        assertThat(result.anonymous).isTrue()
    }

    @Test fun `anonymous defaults to false when claim absent`() {
        val token = svc.signAccess(customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, projectId)
        val result = svc.verify(token)
        assertThat(result).isNotNull
        assertThat(result!!.anonymous).isFalse()
    }

    @Test fun `verify returns null for garbage token`() {
        assertThat(svc.verify("not.a.jwt")).isNull()
    }

    @Test fun `verify throws TokenExpiredException when expired`() {
        val shortSvc = AuthJwtService(AuthJwtKeys(null), "test-issuer", -1)
        val token = shortSvc.signAccess(customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, projectId)
        assertThrows<TokenExpiredException> { shortSvc.verify(token) }
    }

    @Test fun `jwkSet contains public key and no private d`() {
        val json = svc.jwkSetJson()
        assertThat(json).contains("\"kty\":\"OKP\"").contains("\"crv\":\"Ed25519\"")
        assertThat(json).doesNotContain("\"d\":")
    }
}

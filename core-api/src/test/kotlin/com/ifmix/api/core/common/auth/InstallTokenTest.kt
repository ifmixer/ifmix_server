package com.ifmix.api.core.common.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.infra.auth.AuthJwtKeys
import com.ifmix.core.api.infra.auth.AuthJwtService
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * install token（type=5）与 customer token（type=10）的 type/iid claim 校验。
 * 纯逻辑，无 DB/Spring：AuthJwtKeys(null) 生成临时 Ed25519 密钥。
 */
class InstallTokenTest {

    private val svc = AuthJwtService(AuthJwtKeys(null), issuer = "test-issuer")

    @Test
    fun `install token has type=5, iid, and no subject`() {
        val installId = UUID.randomUUID()
        val token = svc.signInstall(installId.toString(), projectId = "proj-a")
        val v = svc.verify(token)!!
        assertThat(v.tokenType).isEqualTo(5)
        assertThat(v.installId).isEqualTo(installId.toString())
        assertThat(v.actorId).isNull() // install token 不设 sub
    }

    @Test
    fun `customer token carries type=10 and iid`() {
        val customerId = UUID.randomUUID()
        val installId = UUID.randomUUID()
        val token = svc.signAccess(
            customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, "proj-a",
            sessionId = "sid-1", anonymous = true, installId = installId.toString(),
        )
        val v = svc.verify(token)!!
        assertThat(v.tokenType).isEqualTo(10)
        assertThat(v.actorId).isEqualTo(customerId.toString())
        assertThat(v.installId).isEqualTo(installId.toString())
    }

    @Test
    fun `token without iid defaults type=10 and null installId`() {
        val token = svc.signAccess(
            UUID.randomUUID().toString(), AuthJwtService.ACTOR_CUSTOMER, "proj-a",
            sessionId = "sid-1", anonymous = false,
        )
        val v = svc.verify(token)!!
        assertThat(v.tokenType).isEqualTo(10)
        assertThat(v.installId).isNull()
    }
}

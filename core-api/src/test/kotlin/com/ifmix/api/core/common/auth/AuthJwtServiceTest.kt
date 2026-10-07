package com.ifmix.core.api.infra.auth

import com.ifmix.api.core.common.auth.testAuthJwtKeys
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date
import java.util.UUID

class AuthJwtServiceTest {
    private val keys = testAuthJwtKeys()
    private val svc = AuthJwtService(keys, "test-issuer", 900)

    private val customerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val projectId = "00000000-0000-0000-0000-000000000099"
    private val sessionId = "00000000-0000-0000-0000-0000000000aa"

    @Test fun `sign then verify returns actorId tokenType and anonymous`() {
        val token = svc.signAccess(
            actorId = customerId.toString(),
            actorType = AuthJwtService.ACTOR_CUSTOMER,
            projectId = projectId,
            sessionId = sessionId,
            anonymous = true,
        )
        val result = svc.verify(token)
        assertThat(result).isNotNull
        assertThat(result!!.actorId).isEqualTo(customerId.toString())
        assertThat(result.tokenType).isEqualTo(AuthJwtService.TOKEN_TYPE_CUSTOMER)
        assertThat(result.projectId).isEqualTo(projectId)
        assertThat(result.anonymous).isEqualTo(true)
        assertThat(result.sessionId).isEqualTo(sessionId)
    }

    @Test fun `anonymous is null when ano claim absent`() {
        // signAccess 总会写 ano claim，手工签一个不带的（模拟老 token）
        val claims = JWTClaimsSet.Builder()
            .issuer("test-issuer")
            .subject(customerId.toString())
            .audience(listOf(projectId))
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 60_000))
            .claim("type", AuthJwtService.TOKEN_TYPE_CUSTOMER)
            .claim("sid", sessionId)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        val result = svc.verify(jwt.serialize())
        assertThat(result).isNotNull
        assertThat(result!!.anonymous).isNull()
    }

    @Test fun `verify returns null when type claim absent`() {
        val claims = JWTClaimsSet.Builder()
            .issuer("test-issuer")
            .subject(customerId.toString())
            .audience(listOf(projectId))
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 60_000))
            .claim("sid", sessionId)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        assertThat(svc.verify(jwt.serialize())).isNull()
    }

    @Test fun `verify returns null when type claim is not an integer`() {
        val claims = JWTClaimsSet.Builder()
            .issuer("test-issuer")
            .subject(customerId.toString())
            .audience(listOf(projectId))
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 60_000))
            .claim("type", "i") // 类型不符的 claim：getIntegerClaim 抛 ParseException，须降级为 null
            .claim("sid", sessionId)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        assertThat(svc.verify(jwt.serialize())).isNull()
    }

    @Test fun `verify returns null for garbage token`() {
        assertThat(svc.verify("not.a.jwt")).isNull()
    }

    @Test fun `verify throws TokenExpiredException when expired`() {
        val shortSvc = AuthJwtService(testAuthJwtKeys("test-key-short"), "test-issuer", -1)
        val token = shortSvc.signAccess(customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, projectId, sessionId)
        assertThrows<TokenExpiredException> { shortSvc.verify(token) }
    }

    @Test fun `jwkSet contains public key and no private d`() {
        val json = svc.jwkSetJson()
        assertThat(json).contains("\"kty\":\"OKP\"").contains("\"crv\":\"Ed25519\"")
        assertThat(json).doesNotContain("\"d\":")
    }
}

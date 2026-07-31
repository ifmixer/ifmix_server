package com.ifmix.api.core.infra.auth

import com.ifmix.api.core.infra.db.UuidV7
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date

/**
 * 自家 app 级 access JWT：EdDSA(Ed25519) 签发/验签 + 暴露公钥 JWKS。
 */
class AuthJwtService(
    private val keys: AuthJwtKeys,
    private val issuer: String,
    private val accessTtlSec: Long = 900,
) {

    companion object {
        const val TOKEN_TYPE = "Bearer"
    }

    fun signAccess(appUserId: String, appId: String): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(appUserId)
            .audience(listOf(appId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .expirationTime(Date(now.time + accessTtlSec * 1000))
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        return jwt.serialize()
    }

    /**
     * 验签 + exp + issuer + audience==expectedAppId。任何失败返回 null（不抛）。
     * 成功返回 appUserId(sub)。
     */
    fun verifyAccess(token: String, expectedAppId: String): String? = try {
        val jwt = SignedJWT.parse(token)
        val pub = keys.publicKeyFor(jwt.header.keyID) ?: return null
        if (!jwt.verify(Ed25519Verifier(pub))) return null
        val claims = jwt.jwtClaimsSet
        val exp = claims.expirationTime ?: return null
        if (exp.before(Date())) return null
        if (claims.issuer != issuer) return null
        if (!claims.audience.contains(expectedAppId)) return null
        claims.subject
    } catch (_: Exception) {
        null
    }

    fun jwkSetJson(): String = keys.jwkSetJson()
}

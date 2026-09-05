package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.entity.common.ActorTypes
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date

/**
 * 自家 app 级 JWT：EdDSA(Ed25519) 签发/验签。主体无关（Medusa v2 actor 模型）。
 *
 * 统一 claim 结构：
 * - sub: actorId（customer / 未来 manager）
 * - aud: appId
 * - act: actorType（10=customer / 20=manager，Int）；用 act 不用 typ（避免与 JOSE header typ 混）
 * - ano: 是否匿名（Boolean，缺省 false）
 */
class AuthJwtService(
    private val keys: AuthJwtKeys,
    private val issuer: String,
    private val accessTtlSec: Long = 900,
) {

    companion object {
        const val TOKEN_TYPE = "Bearer"
        const val ACTOR_CUSTOMER = ActorTypes.CUSTOMER
    }

    /** access token：sub=actorId, act=actorType, ano=anonymous */
    fun signAccess(actorId: String, actorType: ActorType, appId: String, anonymous: Boolean = false): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(actorId)
            .audience(listOf(appId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .expirationTime(Date(now.time + accessTtlSec * 1000))
            .claim("act", actorType)
            .claim("ano", anonymous)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        return jwt.serialize()
    }

    /**
     * 验签 + exp + issuer。任何失败返回 null（不抛）。
     * 成功返回 [VerifiedToken]，actorType 为 Int，由调用方校验一致性。
     */
    fun verify(token: String): VerifiedToken? = try {
        val jwt = SignedJWT.parse(token)
        val pub = keys.publicKeyFor(jwt.header.keyID) ?: return null
        if (!jwt.verify(Ed25519Verifier(pub))) return null
        val claims = jwt.jwtClaimsSet
        val exp = claims.expirationTime ?: return null
        if (exp.before(Date())) return null
        if (claims.issuer != issuer) return null
        VerifiedToken(
            actorId = claims.subject,
            appId = claims.audience?.firstOrNull(),
            actorType = claims.getIntegerClaim("act") ?: ACTOR_CUSTOMER,
            anonymous = runCatching { claims.getBooleanClaim("ano") }.getOrNull() ?: false,
        )
    } catch (_: Exception) {
        null
    }

    fun jwkSetJson(): String = keys.jwkSetJson()
}

data class VerifiedToken(
    val actorId: String?,
    val appId: String?,
    val actorType: ActorType,
    val anonymous: Boolean = false,
) {
    val isCustomer get() = actorType == AuthJwtService.ACTOR_CUSTOMER
}

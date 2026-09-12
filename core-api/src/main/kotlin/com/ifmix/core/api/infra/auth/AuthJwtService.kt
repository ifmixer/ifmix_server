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
 * - aud: projectId
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
    fun signAccess(actorId: String, actorType: ActorType, projectId: String, anonymous: Boolean = false): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(actorId)
            .audience(listOf(projectId))
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
     * 验签 + exp + issuer。
     * - 验签失败/篡改/issuer 不符/结构非法 → 返回 null（不抛）。
     * - 验签通过但**已过期** → 抛 [TokenExpiredException]，供上层映射为 TOKEN_EXPIRED（让前端 refresh）。
     */
    fun verify(token: String): VerifiedToken? {
        val jwt = try {
            val parsed = SignedJWT.parse(token)
            val pub = keys.publicKeyFor(parsed.header.keyID) ?: return null
            if (!parsed.verify(Ed25519Verifier(pub))) return null
            parsed
        } catch (_: Exception) {
            return null
        }
        // 验签已通过：以下是可信 claims。过期单独区分（抛出），其余无效返回 null。
        val claims = jwt.jwtClaimsSet
        val exp = claims.expirationTime ?: return null
        if (exp.before(Date())) throw TokenExpiredException()
        if (claims.issuer != issuer) return null
        return VerifiedToken(
            actorId = claims.subject,
            projectId = claims.audience?.firstOrNull(),
            actorType = claims.getIntegerClaim("act") ?: ACTOR_CUSTOMER,
            anonymous = runCatching { claims.getBooleanClaim("ano") }.getOrNull() ?: false,
        )
    }

    fun jwkSetJson(): String = keys.jwkSetJson()
}

/** 验签通过但 access token 已过期。上层据此返回 TOKEN_EXPIRED，提示前端用 refresh token 换新。 */
class TokenExpiredException : RuntimeException("access token expired")

data class VerifiedToken(
    val actorId: String?,
    val projectId: String?,
    val actorType: ActorType,
    val anonymous: Boolean = false,
) {
    val isCustomer get() = actorType == AuthJwtService.ACTOR_CUSTOMER
}

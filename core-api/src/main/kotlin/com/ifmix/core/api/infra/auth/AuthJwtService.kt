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
 * - sid: sessionId（= 签发本 token 的 refresh token id）；为将来迁移到 Redis session 预留。
 *        refresh 轮换时沿用旧 token 的 sid → 一条会话链共享同一 sid。
 */
class AuthJwtService(
    private val keys: AuthJwtKeys,
    private val issuer: String,
    private val accessTtlSec: Long = 900,
) {

    companion object {
        const val TOKEN_TYPE = "Bearer"
        const val ACTOR_CUSTOMER = ActorTypes.CUSTOMER
        const val TOKEN_TYPE_INSTALL = 5
        const val TOKEN_TYPE_CUSTOMER = 10
        const val TOKEN_TYPE_MANAGER = 20
    }

    /** access token：sub=actorId, act=actorType, ano=anonymous, sid=sessionId */
    fun signAccess(
        actorId: String,
        actorType: ActorType,
        projectId: String,
        sessionId: String,
        anonymous: Boolean = false,
        installId: String? = null,
    ): String {
        val now = Date()
        val builder = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(actorId)
            .audience(listOf(projectId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .expirationTime(Date(now.time + accessTtlSec * 1000))
            .claim("act", actorType)
            .claim("ano", anonymous)
            .claim("sid", sessionId)
            .claim("type", TOKEN_TYPE_CUSTOMER)
        if (installId != null) builder.claim("iid", installId)
        val claims = builder.build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        return jwt.serialize()
    }

    /** install token：type=5, iid=installId, aud=projectId, 无 sub, 永不过期。 */
    fun signInstall(installId: String, projectId: String): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(listOf(projectId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .claim("type", TOKEN_TYPE_INSTALL)
            .claim("iid", installId)
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
        // exp 可选：install token（type=5）永不过期、无 exp claim；customer/manager token 有 exp。
        // 有 exp 才校验过期；无 exp 视为不过期（放行）。
        val exp = claims.expirationTime
        if (exp != null && exp.before(Date())) throw TokenExpiredException()
        if (claims.issuer != issuer) return null
        return VerifiedToken(
            actorId = claims.subject,
            projectId = claims.audience?.firstOrNull(),
            actorType = claims.getIntegerClaim("act") ?: ACTOR_CUSTOMER,
            anonymous = runCatching { claims.getBooleanClaim("ano") }.getOrNull() ?: false,
            sessionId = runCatching { claims.getStringClaim("sid") }.getOrNull(),
            tokenType = claims.getIntegerClaim("type") ?: TOKEN_TYPE_CUSTOMER,
            installId = runCatching { claims.getStringClaim("iid") }.getOrNull(),
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
    /** sessionId（sid claim）= 签发本 token 的 refresh token id；未来 Redis session 用。 */
    val sessionId: String? = null,
    /** token 类型（type claim）：5=install / 10=customer / 20=manager。缺省 10（老 token 兼容）。 */
    val tokenType: Int = AuthJwtService.TOKEN_TYPE_CUSTOMER,
    /** iid claim（可信 installId）：install token 与 customer token 都可能携带。 */
    val installId: String? = null,
) {
    val isCustomer get() = actorType == AuthJwtService.ACTOR_CUSTOMER
}

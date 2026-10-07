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
 * - sub: actorId（customer / 未来 manager）；install token 无 sub
 * - aud: projectId
 * - type: token 类型（5=install / 10=customer / 20=manager，Int）——必填，缺失视为无效 token
 * - ano: 是否匿名（Boolean）；claim 可缺失（老 token / install token），缺失时 VerifiedToken.anonymous = null
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

    /** access token：sub=actorId, type=actorType(10/20), ano=anonymous, sid=sessionId */
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
            .claim("type", actorType)
            .claim("ano", anonymous)
            .claim("sid", sessionId)
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
        // 验签已通过：以下是可信 claims。过期单独区分（抛出）；claim 缺失/类型不符视为无效返回 null
        //（typed getter 对类型不符的 claim 抛 ParseException，需一并兜住）。
        val claims = jwt.jwtClaimsSet
        // exp 可选：install token（type=5）永不过期、无 exp claim；customer/manager token 有 exp。
        // 有 exp 才校验过期；无 exp 视为不过期（放行）。
        val exp = claims.expirationTime
        if (exp != null && exp.before(Date())) throw TokenExpiredException()
        if (claims.issuer != issuer) return null
        return VerifiedToken(
            actorId = claims.subject,
            projectId = claims.audience?.firstOrNull(),
            anonymous = runCatching { claims.getBooleanClaim("ano") }.getOrNull(),
            sessionId = runCatching { claims.getStringClaim("sid") }.getOrNull(),
            tokenType = runCatching { claims.getIntegerClaim("type") }.getOrNull() ?: return null,
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
    /** ano claim：缺失为 null（老 token / install token 无此 claim），不与 false 混淆。 */
    val anonymous: Boolean? = null,
    /** sessionId（sid claim）= 签发本 token 的 refresh token id；未来 Redis session 用。 */
    val sessionId: String? = null,
    /**
     * token 类型（type claim）：5=install / 10=customer / 20=manager。必填——token 无 type claim
     * 视为无效（verify 返回 null）。access token 的 type 即 actorType 编码（ActorType 是 Int
     * typealias，10/20 同值）——不再有独立 act claim。
     */
    val tokenType: Int,
    /** iid claim（可信 installId）：install token 与 customer token 都可能携带。 */
    val installId: String? = null,
)

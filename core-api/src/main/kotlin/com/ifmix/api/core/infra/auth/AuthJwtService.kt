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
 * 自家 app 级 JWT：EdDSA(Ed25519) 签发/验签。
 *
 * 统一 claim 结构：
 * - sub: userId（user token）/ 无（install token）
 * - iid: installId（两种 token 都有）
 * - aud: appId
 * - type: "u"（user）/ "i"（install）
 */
class AuthJwtService(
    private val keys: AuthJwtKeys,
    private val issuer: String,
    private val accessTtlSec: Long = 900,
    val installTtlSec: Long = 86400 * 365,
) {

    companion object {
        const val TOKEN_TYPE = "Bearer"
        const val TYPE_USER = "u"
        const val TYPE_INSTALL = "i"
    }

    /** 用户级 access token：sub=userId, iid=installId, type=u */
    fun signAccess(appUserId: String, installId: String, appId: String): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(appUserId)
            .audience(listOf(appId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .expirationTime(Date(now.time + accessTtlSec * 1000))
            .claim("iid", installId)
            .claim("type", TYPE_USER)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        return jwt.serialize()
    }

    /** Install 级别长期令牌：sub 无, iid=installId, type=i */
    fun signInstall(installId: String, appId: String): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(listOf(appId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .expirationTime(Date(now.time + installTtlSec * 1000))
            .claim("iid", installId)
            .claim("type", TYPE_INSTALL)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        return jwt.serialize()
    }

    /**
     * 验签 + exp + issuer。任何失败返回 null（不抛）。
     * 成功返回 [VerifiedToken]，字段均为原始 String，由调用方校验一致性并转换。
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
            userId = claims.subject,
            installId = claims.getStringClaim("iid"),
            appId = claims.audience?.firstOrNull(),
            type = claims.getStringClaim("type") ?: TYPE_INSTALL,
        )
    } catch (_: Exception) {
        null
    }

    fun jwkSetJson(): String = keys.jwkSetJson()
}

data class VerifiedToken(
    val userId: String?,
    val installId: String?,
    val appId: String?,
    val type: String,
) {
    val isUser get() = type == AuthJwtService.TYPE_USER
    val isInstall get() = type == AuthJwtService.TYPE_INSTALL
}

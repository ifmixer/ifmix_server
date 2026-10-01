package com.ifmix.core.api.infra.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import kotlin.collections.get

/**
 * 持有 Ed25519 签名密钥（OKP，含私钥）+ kid。私钥必须显式配置（env AUTH_JWT_PRIVATE_KEY，JWK JSON）；
 * 缺失直接抛异常 fail-fast——静默生成临时 key 会让 prod「正常启动」但每次重启全员掉登录、JWKS 漂移。
 * 支持多公钥（kid → 公钥）以备轮换（v1 单钥）。
 */
class AuthJwtKeys(privateJwkJson: String) {

    val signingKey: OctetKeyPair

    init {
        if (privateJwkJson.isBlank()) {
            throw IllegalStateException(
                "app.auth.jwt-private-key (env AUTH_JWT_PRIVATE_KEY) is required — " +
                    "generate one with scripts/gen_jwt_key.py"
            )
        }
        signingKey = OctetKeyPair.parse(privateJwkJson)
    }

    val kid: String = signingKey.keyID

    /** kid → 公钥（验签用）。v1 仅当前一把。 */
    private val publicByKid: Map<String, OctetKeyPair> = mapOf(kid to signingKey.toPublicJWK() as OctetKeyPair)

    fun publicKeyFor(kid: String?): OctetKeyPair? = publicByKid[kid]

    /** 对外 JWKS（仅公钥）。 */
    fun jwkSetJson(): String = JWKSet(publicByKid.values.map { it as JWK }).toString()
}

package com.ifmix.api.core.infra.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator

/**
 * 持有 Ed25519 签名密钥（OKP，含私钥）+ kid。私钥来自 env AUTH_JWT_PRIVATE_KEY（JWK JSON）；
 * 缺省时生成临时密钥（仅本地/测试，日志告警）。支持多公钥（kid → 公钥）以备轮换（v1 单钥）。
 */
class AuthJwtKeys(privateJwkJson: String?) {

    val signingKey: OctetKeyPair = if (privateJwkJson.isNullOrBlank()) {
        OctetKeyPairGenerator(Curve.Ed25519).keyID("dev-ephemeral").generate()
    } else {
        OctetKeyPair.parse(privateJwkJson)
    }

    val kid: String = signingKey.keyID

    /** kid → 公钥（验签用）。v1 仅当前一把。 */
    private val publicByKid: Map<String, OctetKeyPair> = mapOf(kid to signingKey.toPublicJWK() as OctetKeyPair)

    fun publicKeyFor(kid: String?): OctetKeyPair? = publicByKid[kid]

    /** 对外 JWKS（仅公钥）。 */
    fun jwkSetJson(): String = JWKSet(publicByKid.values.map { it as com.nimbusds.jose.jwk.JWK }).toString()
}

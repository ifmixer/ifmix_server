package com.ifmix.core.api.infra.http

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.util.Base64
import java.util.HexFormat
import javax.crypto.KeyAgreement

/**
 * 生成 wire 加密 key（新环境 / 轮换用，见设计 §6）：X25519 keypair →
 * 服务端私钥 raw32 base64（`WIRE_CRYPTO_KEYS` / application-local.yml 的 `kid:` 值）
 * + 公钥 hex（客户端 env.ts 的 `wireKey.pubHex`）。
 * 用法：`./gradlew :core-api:genWireDevKey`。kid 手工取 1–255。
 */
fun main() {
    val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
    require(raw.any { it != 0.toByte() }) { "随机数异常" }
    val priv = KeyFactory.getInstance("X25519").generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, raw))
    // 公钥 = X25519(priv, 基点 u=9)，与 WireCrypto 同法（Corretto 25 无 XDH* 接口，不可走 KeyFactory 互转）
    val pubRaw = x25519(priv, ByteArray(32).also { it[0] = 9 })
    println("WIRE_CRYPTO_KEYS=\"1:${Base64.getEncoder().encodeToString(raw)}\"")
    println("pubHex=${HexFormat.of().formatHex(pubRaw)}")
}

private fun x25519(priv: PrivateKey, peerRaw: ByteArray): ByteArray {
    val ka = KeyAgreement.getInstance("X25519")
    ka.init(priv)
    ka.doPhase(KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(X509_WIRE_PREFIX + peerRaw)), true)
    return ka.generateSecret()
}

private val X509_WIRE_PREFIX = HexFormat.of().parseHex("302a300506032b656e032100")

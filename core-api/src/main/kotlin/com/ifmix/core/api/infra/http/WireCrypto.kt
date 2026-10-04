package com.ifmix.core.api.infra.http

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.KeyAgreement
import javax.crypto.spec.HKDFParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * x-proto-version: 2 应用层加密（HPKE 思路，无状态）：X25519 + HKDF-SHA256 + ChaCha20-Poly1305。
 * 客户端只内置服务端公钥；每个请求生成临时 X25519 密钥对，拆包拿到公钥也解不了别人的流量。
 *
 * 请求 body：ver(1)=2 | kid(1) | ephPub(32) | nonce(12) | AEAD(reqKey, nonce, ts_ms(8, BE) ‖ 原 body, aad = 前 34 字节)
 * 响应 body：nonce(12) | AEAD(resKey, nonce, 原 body, aad = ephPub)
 * okm = HKDF(ikm = X25519(serverPriv, ephPub), salt = ephPub ‖ serverPub, info = "ifmix-wire-v2", L = 64)
 *       → reqKey = okm[0,32) / resKey = okm[32,64)
 *
 * AAD 不含 path：CF/nginx 可能改写路径，绑 path 会让正常用户因网关配置变化解密失败。
 * 与客户端实现（antique: apps/shared/src/api/wireCrypto.ts）必须逐字节一致。
 *
 * @param keys kid → X25519 原始私钥（32 字节）。多 kid 并存以便轮换。
 */
class WireCrypto(keys: Map<Int, ByteArray>) {

    private class Key(val priv: PrivateKey, val pubRaw: ByteArray)

    private val keys: Map<Int, Key> = keys.mapValues { (_, raw) ->
        require(raw.size == 32) { "wire-crypto private key must be 32 raw bytes" }
        val priv = KeyFactory.getInstance("X25519").generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, raw))
        // 公钥 = X25519(priv, 基点 u=9)
        Key(priv, x25519(priv, BASE_POINT))
    }

    val isEnabled get() = keys.isNotEmpty()

    /** 解密成功的请求：明文 body + 客户端时间戳 + 用于加密响应的 [seal]。 */
    class Opened(val body: ByteArray, val clientTsMs: Long, private val resKey: ByteArray, private val ephPub: ByteArray) {
        fun seal(plain: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_LEN).also { RNG.nextBytes(it) }
            return nonce + aead(Cipher.ENCRYPT_MODE, resKey, nonce, ephPub, plain)
        }
    }

    /** 解密请求 body；格式非法 / 未知 kid / 认证失败一律抛 [WireCryptoException]（不区分原因）。 */
    fun open(payload: ByteArray): Opened {
        if (payload.size < HEADER_LEN + NONCE_LEN + TAG_LEN + TS_LEN || payload[0].toInt() != VERSION) throw WireCryptoException()
        val key = keys[payload[1].toInt() and 0xff] ?: throw WireCryptoException()
        val ephPub = payload.copyOfRange(2, HEADER_LEN)
        val nonce = payload.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
        val (reqKey, resKey) = try {
            derive(x25519(key.priv, ephPub), ephPub, key.pubRaw)
        } catch (_: Exception) {
            throw WireCryptoException() // 低阶点等非法公钥
        }
        val plain = try {
            aead(Cipher.DECRYPT_MODE, reqKey, nonce, payload.copyOfRange(0, HEADER_LEN), payload.copyOfRange(HEADER_LEN + NONCE_LEN, payload.size))
        } catch (_: Exception) {
            throw WireCryptoException()
        }
        val ts = ByteBuffer.wrap(plain, 0, TS_LEN).long
        return Opened(plain.copyOfRange(TS_LEN, plain.size), ts, resKey, ephPub)
    }

    /** 测试 / 本地脚本用：kid 对应的原始公钥。 */
    fun publicKey(kid: Int): ByteArray? = keys[kid]?.pubRaw

    companion object {
        const val VERSION = 2
        const val HEADER_LEN = 34 // ver + kid + ephPub
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        const val TS_LEN = 8
        private val INFO = "ifmix-wire-v2".toByteArray()
        private val RNG = SecureRandom()
        private val BASE_POINT = ByteArray(32).also { it[0] = 9 }
        private val X509_PREFIX = HexFormat.of().parseHex("302a300506032b656e032100")

        /** 解析配置 `kid:base64私钥,kid:base64私钥`（env 友好）。空串 → 无 key（v2 请求全部解密失败 → 客户端降级明文）。 */
        fun parse(spec: String): WireCrypto = WireCrypto(
            spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate {
                val (kid, b64) = it.split(':', limit = 2)
                kid.trim().toInt() to Base64.getDecoder().decode(b64.trim())
            },
        )

        private fun x25519(priv: PrivateKey, peerRaw: ByteArray): ByteArray = KeyAgreement.getInstance("X25519").run {
            init(priv)
            doPhase(rawToPub(peerRaw), true)
            generateSecret()
        }

        private fun rawToPub(raw: ByteArray): PublicKey =
            KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))

        private fun derive(shared: ByteArray, ephPub: ByteArray, serverPub: ByteArray): Pair<ByteArray, ByteArray> {
            val okm = KDF.getInstance("HKDF-SHA256").deriveData(
                HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(ephPub + serverPub).thenExpand(INFO, 64),
            )
            return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
        }

        private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
            Cipher.getInstance("ChaCha20-Poly1305").run {
                init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
                updateAAD(aad)
                doFinal(input)
            }
    }
}

class WireCryptoException : RuntimeException("wire decrypt failed")

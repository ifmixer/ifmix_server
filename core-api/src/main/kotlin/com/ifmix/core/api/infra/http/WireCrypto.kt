package com.ifmix.core.api.infra.http

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * x-proto-version: 2 应用层加密（无状态 HPKE 思路）：X25519 + HKDF-SHA256 + AES-256-GCM（tag 16 字节）。
 * 客户端只内置服务端公钥；每个请求生成临时 X25519 密钥对，拆包拿到公钥也解不了任何流量（含别人的）。
 *
 * 请求 body（`Content-Type: application/octet-stream`，前 34 字节为 AAD）：
 * ```
 * ver(1)=2 | kid(1) | ephPub(32) | nonce(12) | GCM(reqKey, nonce, ts_ms(8 BE) ‖ 原 JSON) ‖ tag(16)
 * ```
 * 响应 body（HTTP status 保持原值，AAD = ephPub ‖ flags）：
 * ```
 * flags(1) | nonce(12) | GCM(resKey, nonce, payload) ‖ tag(16)
 * flags bit0 = 1 → payload = gzip(原文)；bit1–7 保留必须为 0；原文 > [GZIP_THRESHOLD] 才 gzip
 * ```
 *
 * 密钥派生：`okm = HKDF-SHA256(ikm = X25519(serverPriv, ephPub), salt = ephPub ‖ serverPub, info = "ifmix-wire-v2", L = 64)`
 * → reqKey = okm[0,32) / resKey = okm[32,64)。
 *
 * AAD 不含 path：CF/nginx 可能改写路径，绑 path 会让正常用户因网关配置变化解密失败。
 * 与客户端实现（antique: apps/shared/src/api/wireCrypto.ts）必须逐字节一致；跨语言固定向量见 WireCryptoTest。
 *
 * 注：Corretto 25 裁掉了 java.security.interfaces.XDH*，因此私钥/公钥只走 XEC* spec 与
 * KeyAgreement——公钥由 X25519(priv, 基点 u=9) 推导，不能用 PrivateKey.getPublic()（JCA 本就无此 API）。
 *
 * @param keys kid → X25519 原始私钥（32 字节）。多 kid 并存以便轮换。
 */
class WireCrypto(keys: Map<Int, ByteArray>) {

    private class Key(val priv: PrivateKey, val pubRaw: ByteArray)

    private val keys: Map<Int, Key> = keys.mapValues { (_, raw) ->
        require(raw.size == KEY_LEN) { "wire-crypto 私钥必须为 $KEY_LEN 字节 raw，got ${raw.size}" }
        val priv = KeyFactory.getInstance("X25519").generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, raw))
        Key(priv, x25519(priv, BASE_POINT))
    }

    val isEnabled: Boolean get() = keys.isNotEmpty()

    /** 解密成功的请求：明文 body + 客户端时间戳 + kid（日志用）+ 用于加密响应的 [seal]。 */
    class Opened(
        val body: ByteArray,
        val clientTsMs: Long,
        val kid: Int,
        private val resKey: ByteArray,
        private val ephPub: ByteArray,
    ) {
        /** 加密响应 body。gzip 与 flags 只在这里定义。 */
        fun seal(plain: ByteArray): ByteArray {
            val gzip = plain.size > GZIP_THRESHOLD
            val flags = if (gzip) FLAG_GZIP else 0
            val nonce = ByteArray(NONCE_LEN).also(RNG::nextBytes)
            return byteArrayOf(flags.toByte()) + nonce +
                aead(Cipher.ENCRYPT_MODE, resKey, nonce, ephPub + byteArrayOf(flags.toByte()), if (gzip) plain.gzip() else plain)
        }
    }

    /**
     * 解密请求 body。格式非法 / 未知 kid / 认证失败 / 低阶点一律抛 [WireCryptoException]（不区分原因，
     * filter 对外统一 400003）；[WireCryptoException.kid] 仅用于服务端日志。
     */
    fun open(payload: ByteArray): Opened {
        if (payload.size < HEADER_LEN + NONCE_LEN + TAG_LEN + TS_LEN || payload[0].toInt() != VERSION) throw WireCryptoException()
        val kid = payload[1].toInt() and 0xff
        val key = keys[kid] ?: throw WireCryptoException(kid)
        val ephPub = payload.copyOfRange(2, HEADER_LEN)
        val nonce = payload.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
        return try {
            // 低阶点黑名单（RFC 7748 erratum bad-input 编码）：JDK 的常数时间 Montgomery ladder 对这些点
            // 不拒绝、照常算出 shared（落在 ≤8 个可枚举值里）——攻击者伪造请求即可枚举 resKey 解受害者响应，必须显式拒。
            if (LOW_ORDER_EPH_PUBS.any { ephPub.contentEquals(it) }) throw WireCryptoException(kid)
            val shared = x25519(key.priv, ephPub)
            val okm = KDF.getInstance("HKDF-SHA256").deriveData(
                HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(ephPub + key.pubRaw).thenExpand(INFO, OKM_LEN),
            )
            val plain = aead(
                Cipher.DECRYPT_MODE, okm.copyOfRange(0, KEY_LEN), nonce,
                payload.copyOfRange(0, HEADER_LEN),
                payload.copyOfRange(HEADER_LEN + NONCE_LEN, payload.size),
            )
            require(plain.size >= TS_LEN) { "missing ts" } // 客户端恒写 8 字节 ts，缺失 = 恶意构造
            Opened(
                body = plain.copyOfRange(TS_LEN, plain.size),
                clientTsMs = ByteBuffer.wrap(plain).long,
                kid = kid,
                resKey = okm.copyOfRange(KEY_LEN, OKM_LEN),
                ephPub = ephPub,
            )
        } catch (_: Exception) {
            throw WireCryptoException(kid)
        }
    }

    /** 测试 / 本地脚本用：kid 对应的原始公钥。 */
    fun publicKey(kid: Int): ByteArray? = keys[kid]?.pubRaw

    companion object {
        const val VERSION = 2
        const val HEADER_LEN = 34 // ver(1) + kid(1) + ephPub(32)
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        const val TS_LEN = 8
        const val KEY_LEN = 32
        const val OKM_LEN = 64

        /** 响应原文超过该字节数才 gzip（4096 不压、4097 压）。 */
        const val GZIP_THRESHOLD = 4096
        private const val FLAG_GZIP = 1 // bit0；bit1–7 保留，服务端只会写 0 或 1

        private val INFO = "ifmix-wire-v2".toByteArray()
        private val RNG = SecureRandom()
        private val BASE_POINT = ByteArray(KEY_LEN).also { it[0] = 9 } // RFC 7748：基点 u = 9（小端）
        private val X509_PREFIX = java.util.HexFormat.of().parseHex("302a300506032b656e032100")

        /**
         * 低阶（小序）点 ephPub 黑名单。x25519 的 8-torsion 点 x 坐标 {0, 2^255−1, p−2, p−14, p−22}
         * （p = 2^255−19）；每个 x 两种 32 字节小端编码（符号位 0/1）= 10 个值。
         * 全零 32B（x=0）被 JDK 直接 reject（InvalidKeyException），列在此仅作完整性。
         * 命中任一则拒绝请求——否则攻击者可枚举 ≤8 个 shared 值解受害者响应（resKey 固定 32B 密钥）。
         */
        private val LOW_ORDER_EPH_PUBS: List<ByteArray> = listOf(
            // x = 0（全零编码）
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000080",
            // x = 2^255 - 1（order 2）
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            // x = p - 2（order 4）
            "ebffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "ebffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            // x = p - 14（order 4）
            "dfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "dfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            // x = p - 22（order 4）
            "d7ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "d7ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        ).map { java.util.HexFormat.of().parseHex(it) }

        /**
         * 解析配置 `kid:base64私钥,kid:base64私钥`（env 友好）。空串 → 无 key（isEnabled=false，
         * v2 请求全部 400003 → 客户端降级明文）。配置错误直接抛（启动期 fail-fast，区别于 payload 错误）。
         */
        fun parse(spec: String): WireCrypto = WireCrypto(
            spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
                val parts = entry.split(':', limit = 2)
                require(parts.size == 2) { "wire-crypto key 项应为 kid:base64，got \"$entry\"" }
                val kid = parts[0].trim().toInt()
                require(kid in 1..255) { "wire-crypto kid 必须在 1..255，got $kid" }
                kid to Base64.getDecoder().decode(parts[1].trim())
            },
        )

        private fun x25519(priv: PrivateKey, peerRaw: ByteArray): ByteArray {
            val ka = KeyAgreement.getInstance("X25519")
            ka.init(priv)
            ka.doPhase(KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + peerRaw)), true)
            return ka.generateSecret()
        }

        private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
                updateAAD(aad)
                doFinal(input)
            }

        private fun ByteArray.gzip(): ByteArray = ByteArrayOutputStream().use { buf ->
            GZIPOutputStream(buf).use { it.write(this) }
            buf.toByteArray()
        }
    }
}

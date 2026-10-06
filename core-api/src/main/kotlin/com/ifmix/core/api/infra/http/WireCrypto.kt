package com.ifmix.core.api.infra.http

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.hpke.HPKE

/**
 * `x-wirep-version: 2` 应用层加密（RFC 9180 HPKE base mode）：
 * Suite = DHKEM(X25519, HKDF-SHA256) 0x0020 / HKDF-SHA256 0x0001 / AES-256-GCM 0x0002，
 * 全部走 BouncyCastle `org.bouncycastle.crypto.hpke`（RFC 9180 标准实现），不手写任何原语/常数。
 *
 * 请求 body（`Content-Type: application/octet-stream`）：
 * ```
 * ver(1)=2 | kid(1) | enc(32) | flags(1) | HPKE-Seal(密文) ‖ tag(16)
 * aad = 前 35 字节（ver‖kid‖enc‖flags，flags 参与 AAD 防翻转）
 * pt  = ts_ms(8, BE) ‖ body；flags bit0=1 → pt 整体 gzip 后再加密（pt > 4096 才压）
 * ```
 * 响应 body（HTTP status 保持原值）：
 * ```
 * flags(1) | nonce(12) | AES-256-GCM(resKey, nonce, aad = enc‖flags(33B), payload) ‖ tag(16)
 * resKey = HPKE-Export(context, "ifmix-wire-v2-res", L=32)；nonce 每响应 SecureRandom 现生成
 * flags bit0=1 → payload gzip（>4096）；未知 flag 位客户端必须失败
 * ```
 * 解压上限 [maxDecompressedBytes]（默认 1MB，防 zip-bomb），超限抛 [WireCryptoException] → 400003。
 * 与客户端实现（antique: packages/client-sdk/packages/api/src/api/wireCrypto.ts）必须逐字节一致；
 * RFC 官方向量测试见 WireCryptoTest（core-api/src/test/resources/wire-v3/，目录名历史遗留）。
 *
 * @param keys kid → X25519 原始私钥（32 字节）。多 kid 并存以便轮换。
 * @param maxDecompressedBytes 请求解密后（gunzip 后）明文 pt 的字节上限。
 */
class WireCrypto(
    keys: Map<Int, ByteArray>,
    private val maxDecompressedBytes: Int = DEFAULT_MAX_DECOMPRESSED_BYTES,
) {

    private class Key(val kp: org.bouncycastle.crypto.AsymmetricCipherKeyPair, val pubRaw: ByteArray)

    private val hpke = WireCrypto.SUITE
    private val keys: Map<Int, Key> = keys.mapValues { (_, raw) ->
        require(raw.size == KEY_LEN) { "wire-crypto 私钥必须为 $KEY_LEN 字节 raw，got ${raw.size}" }
        val kp = hpke.deserializePrivateKey(raw, null) // 内部推导公钥（RFC 9180 DHKEM X25519）
        Key(kp, hpke.serializePublicKey(kp.public))
    }

    val isEnabled: Boolean get() = keys.isNotEmpty()

    /** 解密成功的请求：明文 body + 客户端时间戳 + kid（日志用）+ 用于加密响应的 [seal]。 */
    class Opened(
        val body: ByteArray,
        val clientTsMs: Long,
        val kid: Int,
        private val resKey: ByteArray,
        private val enc: ByteArray,
    ) {

        /** 加密响应 body。gzip 与 flags 只在这里定义。 */
        fun seal(plain: ByteArray): ByteArray {
            val gzip = plain.size > GZIP_THRESHOLD
            val flags = if (gzip) FLAG_GZIP else 0
            val nonce = ByteArray(NONCE_LEN).also(RNG::nextBytes)
            // 响应 AAD = enc(32) ‖ flags(1) = 33B
            val aad = enc + byteArrayOf(flags.toByte())
            return byteArrayOf(flags.toByte()) + nonce +
                aead(Cipher.ENCRYPT_MODE, resKey, nonce, aad, if (gzip) plain.gzip() else plain)
        }
    }

    /**
     * 解密请求 body。格式非法 / 未知 kid / 未知 flag / HPKE 认证失败 / 解压超限一律抛 [WireCryptoException]
     * （不区分原因，filter 对外统一 400003）；[WireCryptoException.kid] 仅用于服务端日志。
     */
    fun open(payload: ByteArray): Opened {
        // 最短合法请求 = 35B 头 + 8B ts + 16B GCM tag
        if (payload.size < HEADER_LEN + TS_LEN + TAG_LEN || payload[0].toInt() != VERSION) throw WireCryptoException()
        val kid = payload[1].toInt() and 0xff
        val key = keys[kid] ?: throw WireCryptoException(kid)
        val flags = payload[HEADER_LEN - 1].toInt() and 0xff
        if (flags and FLAG_UNKNOWN_BITS != 0) throw WireCryptoException(kid) // 未知 flag 位 = 协议版本不匹配，直接拒
        val enc = payload.copyOfRange(ENC_OFFSET, ENC_OFFSET + ENC_LEN)
        // 请求 AAD = 前 35 字节（ver‖kid‖enc‖flags）
        val aad = payload.copyOfRange(0, HEADER_LEN)
        return try {
            val ctx = hpke.setupBaseR(enc, key.kp, INFO)
            val pt = if (flags and FLAG_GZIP == FLAG_GZIP) {
                val plain = ctx.open(aad, payload.copyOfRange(HEADER_LEN, payload.size))
                val unzipped = plain.unGzip()
                require(unzipped.size <= maxDecompressedBytes) { "decompressed ${unzipped.size} > limit $maxDecompressedBytes" }
                unzipped
            } else {
                ctx.open(aad, payload.copyOfRange(HEADER_LEN, payload.size))
            }
            require(pt.size >= TS_LEN) { "missing ts" } // 客户端恒写 8 字节 ts，缺失 = 恶意构造
            // resKey = HPKE-Export(context, "ifmix-wire-v2-res", 32)；context 生命周期仅限该请求
            val resKey = ctx.export(RESP_EXPORT_CONTEXT, KEY_LEN)
            Opened(
                body = pt.copyOfRange(TS_LEN, pt.size),
                clientTsMs = ByteBuffer.wrap(pt).long,
                kid = kid,
                resKey = resKey,
                enc = enc,
            )
        } catch (e: WireCryptoException) {
            throw e
        } catch (_: Exception) {
            throw WireCryptoException(kid)
        }
    }

    /** 测试 / 本地脚本用：kid 对应的原始公钥（32 字节）。 */
    fun publicKey(kid: Int): ByteArray? = keys[kid]?.pubRaw

    companion object {
        const val VERSION = 2
        const val HEADER_LEN = 35 // ver(1) + kid(1) + enc(32) + flags(1)；请求 AAD = 该 35B 整段
        const val ENC_LEN = 32
        const val KEY_LEN = 32
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        const val TS_LEN = 8

        /** 请求/响应原文超过该字节数才 gzip（4096 不压、4097 压）。 */
        const val GZIP_THRESHOLD = 4096
        const val DEFAULT_MAX_DECOMPRESSED_BYTES = 1048576 // 1MB，zip-bomb 防护默认值

        private const val FLAG_GZIP = 1 // bit0；bit1–7 保留（未知位必须拒绝）
        private const val FLAG_UNKNOWN_BITS = 0b1111_1110

        private const val ENC_OFFSET = 2 // ver(1) + kid(1) 之后

        /** RFC 9180 官方 suite（BouncyCastle 常量，不手写数字）。 */
        val SUITE = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

        private val INFO = "ifmix-wire-v2".toByteArray()
        private val RESP_EXPORT_CONTEXT = "ifmix-wire-v2-res".toByteArray()
        private val RNG = SecureRandom()

        /**
         * 解析配置 `kid:base64私钥,kid:base64私钥`（env 友好，key 格式同 v2：X25519 32B raw base64）。
         * 空串 → 无 key（isEnabled=false；required 模式下 /api/…（api 前缀路径） 请求全部 400003 —— 启动配置错误会大声失败，这是有意的）。
         * 配置错误直接抛（启动期 fail-fast，区别于 payload 错误）。
         */
        fun parse(spec: String, maxDecompressedBytes: Int = DEFAULT_MAX_DECOMPRESSED_BYTES): WireCrypto =
            WireCrypto(
                spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
                    val parts = entry.split(':', limit = 2)
                    require(parts.size == 2) { "wire-crypto key 项应为 kid:base64，got \"$entry\"" }
                    val kid = parts[0].trim().toInt()
                    require(kid in 1..255) { "wire-crypto kid 必须在 1..255，got $kid" }
                    kid to Base64.getDecoder().decode(parts[1].trim())
                },
                maxDecompressedBytes,
            )

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

        private fun ByteArray.unGzip(): ByteArray =
            GZIPInputStream(java.io.ByteArrayInputStream(this)).use { it.readAllBytes() }
    }
}

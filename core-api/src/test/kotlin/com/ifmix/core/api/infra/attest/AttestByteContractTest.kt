package com.ifmix.core.api.infra.attest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 字节契约测试（规格 §7「字节契约」、§3.1）。
 *
 * 用与实现无关的独立推导（测试内手工拼 payload + HMAC）构造固定向量，
 * 锁定 challenge / clientDataHash / expectedNonce 的逐字节公式：
 *
 * ```
 * payload      = ver(1)=1 ‖ issuedAtSec(8, BE) ‖ random(16)
 * mac          = HMAC-SHA256(secret, "ifmix-attest-ch-v1\n" ‖ projectId ‖ "\n" ‖ payload)
 * challengeStr = base64urlNoPadding(payload ‖ mac)         // 57 字节 → 76 字符
 * clientDataHash = SHA256(UTF8(challengeStr))               // 客户端 Expo IntegrityModule 同公式
 * expectedNonce  = SHA256(authData ‖ clientDataHash)        // Apple credCert 扩展 1.2.840.113635.100.8.2
 * ```
 *
 * 客户端（antique）用同一组向量做契约测试；改公式必须三端同步。
 */
class AttestByteContractTest {

    companion object {
        private const val PROJECT_ID = "antique"
        private const val ISSUED_AT_SEC = 1_700_000_000L

        /** 32 字节测试 secret（"0123456789abcdef" × 2，仅测试用，不属于任何环境）。 */
        private val SECRET = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.US_ASCII)

        /** 固定 random(16) = 00..0F。 */
        private val FIXED_RANDOM = (0 until 16).map { it.toByte() }.toByteArray()

        /** 固定 authData（32 字节任意值，向量记录用）。 */
        private val FIXED_AUTH_DATA = (1..32).map { it.toByte() }.toByteArray()

        // ---- 冻结向量（2026-10-04 生成；改动即破坏契约，需三端同步）----
        const val VECTOR_CHALLENGE_STR =
            "AQAAAABlU_EAAAECAwQFBgcICQoLDA0OD1oFU4YVfEp9shlXoUhW1ChaDR3wRLRdYkL7TSj4CYYR"
        const val VECTOR_CLIENT_DATA_HASH_HEX =
            "f64bce63800f35e67060a6bdfed00addbc42c721b6f118d8f47e00921c5e7b85"
        const val VECTOR_NONCE_HEX =
            "04b815a3b62ec61c126559c71e04d017d84c898a0aa7e61efbf46f0e7f3b1ddf"

        private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

        private fun sha256(message: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(message)

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        /** 独立于 AttestChallengeCodec 的推导（作为第二实现交叉验证）。 */
        private fun buildChallengeStr(): String {
            val payload = ByteArray(AttestChallengeCodec.PAYLOAD_LEN)
            payload[0] = AttestChallengeCodec.VERSION
            ByteBuffer.wrap(payload, AttestChallengeCodec.ISSUED_AT_OFFSET, AttestChallengeCodec.ISSUED_AT_LEN)
                .putLong(ISSUED_AT_SEC)
            System.arraycopy(FIXED_RANDOM, 0, payload, AttestChallengeCodec.RANDOM_OFFSET, AttestChallengeCodec.RANDOM_LEN)
            val message = AttestChallengeCodec.MAC_PREFIX.toByteArray(Charsets.UTF_8) +
                PROJECT_ID.toByteArray(Charsets.UTF_8) +
                byteArrayOf('\n'.code.toByte()) +
                payload
            val mac = hmacSha256(SECRET, message)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload + mac)
        }
    }

    @Test
    fun `challenge format - 57 bytes to 76 chars`() {
        val challengeStr = buildChallengeStr()
        assertThat(challengeStr).hasSize(76)
        assertThat(Base64.getUrlDecoder().decode(challengeStr)).hasSize(57)
    }

    @Test
    fun `frozen vector - codec verifies against independent derivation`() {
        val challengeStr = buildChallengeStr()

        // 独立推导必须命中冻结向量（公式未漂移）
        assertThat(challengeStr).isEqualTo(VECTOR_CHALLENGE_STR)

        // 实现必须接受独立推导出的向量
        val codec = AttestChallengeCodec(listOf(SECRET), Clock.fixed(Instant.ofEpochSecond(ISSUED_AT_SEC), ZoneOffset.UTC))
        assertThat(codec.verify(PROJECT_ID, challengeStr)).isEqualTo(AttestChallengeCodec.Verification.Valid)

        // projectId 绑定
        assertThat(codec.verify("other-project", challengeStr))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
    }

    @Test
    fun `frozen vector - clientDataHash formula`() {
        val clientDataHash = sha256(VECTOR_CHALLENGE_STR.toByteArray(Charsets.UTF_8))
        assertThat(hex(clientDataHash)).isEqualTo(VECTOR_CLIENT_DATA_HASH_HEX)
    }

    @Test
    fun `frozen vector - expectedNonce formula`() {
        val clientDataHash = hexToBytes(VECTOR_CLIENT_DATA_HASH_HEX)
        val nonce = sha256(FIXED_AUTH_DATA + clientDataHash)
        assertThat(hex(nonce)).isEqualTo(VECTOR_NONCE_HEX)
    }

    private fun hexToBytes(hexStr: String): ByteArray =
        hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

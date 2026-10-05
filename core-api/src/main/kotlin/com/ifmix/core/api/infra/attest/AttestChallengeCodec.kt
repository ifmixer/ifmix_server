package com.ifmix.core.api.infra.attest

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 无状态 HMAC challenge 签发 / 校验（规格 §3.1）。
 *
 * ```
 * payload      = ver(1)=1 ‖ issuedAtSec(8, BE) ‖ random(16)          // 25 字节
 * mac          = HMAC-SHA256(secret, "ifmix-attest-ch-v1\n" ‖ projectId ‖ "\n" ‖ payload)
 * challengeStr = base64urlNoPadding(payload ‖ mac)                   // 57 字节 → 76 字符 ASCII
 * ```
 *
 * - 签发只用 `secrets[0]`（current）；校验依次尝试 current → previous，恒定时间比较。
 * - 校验窗口：`now - issuedAt ∈ [-30s, +300s]`（-30s 给多实例时钟差留余量）。
 * - 签发和校验都是纯计算，不依赖 Redis；一次性消费由 [AttestReplayGuard] 负责。
 * - secret 支持 `current[,previous]` 两个；env 层（`APP_ATTEST_CHALLENGE_SECRET`）的解析由接线层做，
 *   本类只接收已解码的字节。
 *
 * `clock` 构造注入（测试用固定时钟）。
 */
class AttestChallengeCodec(
    private val secrets: List<ByteArray>,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()

    init {
        require(secrets.isNotEmpty()) { "at least one challenge secret is required" }
        secrets.forEachIndexed { i, s ->
            require(s.isNotEmpty()) { "challenge secret #$i must not be empty" }
        }
    }

    /** 签发一个新 challenge（76 字符 base64url，无 padding）。 */
    fun issue(projectId: String): String {
        val payload = ByteArray(PAYLOAD_LEN)
        payload[0] = VERSION
        ByteBuffer.wrap(payload, ISSUED_AT_OFFSET, ISSUED_AT_LEN).putLong(clock.instant().epochSecond)
        val nonce = ByteArray(RANDOM_LEN)
        random.nextBytes(nonce)
        System.arraycopy(nonce, 0, payload, RANDOM_OFFSET, RANDOM_LEN)

        val mac = hmac(secrets.first(), macMessage(projectId, payload))

        val out = ByteArray(TOTAL_LEN)
        System.arraycopy(payload, 0, out, 0, PAYLOAD_LEN)
        System.arraycopy(mac, 0, out, PAYLOAD_LEN, MAC_LEN)
        return B64_URL_ENCODER.encodeToString(out)
    }

    /**
     * 校验 challenge 的签名与时效。任一项不通过 → [Verification.Invalid]
     * （reason 区分 challenge_invalid / challenge_expired）。
     */
    fun verify(projectId: String, challengeStr: String): Verification {
        val decoded: ByteArray = try {
            B64_URL_DECODER.decode(challengeStr)
        } catch (_: IllegalArgumentException) {
            return Verification.Invalid(Reason.CHALLENGE_INVALID)
        }
        if (decoded.size != TOTAL_LEN) return Verification.Invalid(Reason.CHALLENGE_INVALID)
        if (decoded[0] != VERSION) return Verification.Invalid(Reason.CHALLENGE_INVALID)

        val payload = decoded.copyOfRange(0, PAYLOAD_LEN)
        val mac = decoded.copyOfRange(PAYLOAD_LEN, TOTAL_LEN)

        // 恒定时间比较（MessageDigest.isEqual）；先试 current，再试 previous。
        val macMatches = secrets.any { candidate ->
            MessageDigest.isEqual(hmac(candidate, macMessage(projectId, payload)), mac)
        }
        if (!macMatches) return Verification.Invalid(Reason.CHALLENGE_INVALID)

        val issuedAtSec = ByteBuffer.wrap(payload, ISSUED_AT_OFFSET, ISSUED_AT_LEN).long
        val deltaSec = clock.instant().epochSecond - issuedAtSec
        if (deltaSec > TTL_SEC) return Verification.Invalid(Reason.CHALLENGE_EXPIRED)
        if (deltaSec < -CLOCK_SKEW_SEC) return Verification.Invalid(Reason.CHALLENGE_INVALID)
        return Verification.Valid
    }

    /** message = "ifmix-attest-ch-v1\n" ‖ projectId ‖ "\n" ‖ payload（纯拼接，不做哈希）。 */
    private fun macMessage(projectId: String, payload: ByteArray): ByteArray {
        val pid = projectId.toByteArray(Charsets.UTF_8)
        val out = ByteArray(MAC_PREFIX_UTF8.size + pid.size + 1 + payload.size)
        var pos = 0
        System.arraycopy(MAC_PREFIX_UTF8, 0, out, pos, MAC_PREFIX_UTF8.size)
        pos += MAC_PREFIX_UTF8.size
        System.arraycopy(pid, 0, out, pos, pid.size)
        pos += pid.size
        out[pos++] = '\n'.code.toByte()
        System.arraycopy(payload, 0, out, pos, payload.size)
        return out
    }

    private fun hmac(secret: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        return mac.doFinal(message)
    }

    sealed interface Verification {
        data object Valid : Verification
        data class Invalid(val reason: Reason) : Verification
    }

    /** reason 取值对齐规格 §5.5 的日志 reason 枚举。 */
    enum class Reason(val code: String) {
        CHALLENGE_INVALID("challenge_invalid"),
        CHALLENGE_EXPIRED("challenge_expired"),
    }

    companion object {
        const val VERSION: Byte = 1
        const val ISSUED_AT_OFFSET = 1
        const val ISSUED_AT_LEN = 8
        const val RANDOM_LEN = 16
        const val RANDOM_OFFSET = ISSUED_AT_OFFSET + ISSUED_AT_LEN // 9
        const val PAYLOAD_LEN = 1 + ISSUED_AT_LEN + RANDOM_LEN     // 25
        const val MAC_LEN = 32
        const val TOTAL_LEN = PAYLOAD_LEN + MAC_LEN                // 57
        const val CHALLENGE_STR_LEN = 76                           // 57 字节 base64url 无 padding
        const val MAC_PREFIX = "ifmix-attest-ch-v1\n"

        /** issuedAt 允许超前于校验时钟的秒数（多实例时钟差余量）。 */
        const val CLOCK_SKEW_SEC = 30L

        /** 有效期（服务端接受窗口）；对客户端返回 expiresInSec=270。 */
        const val TTL_SEC = 300L

        /** 对客户端返回的时间预算（§5.1：服务端接受 300s，客户端 270s，留传输与 attest 余量）。 */
        const val CHALLENGE_CLIENT_TTL_SEC = 270

        private val MAC_PREFIX_UTF8 = MAC_PREFIX.toByteArray(Charsets.UTF_8)
        private val B64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val B64_URL_DECODER = Base64.getUrlDecoder()
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

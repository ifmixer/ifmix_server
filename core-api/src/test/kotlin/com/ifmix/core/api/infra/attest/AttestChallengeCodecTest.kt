package com.ifmix.core.api.infra.attest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * AttestChallengeCodec 单测：注入固定 Clock，不依赖真实时间（规格 §7）。
 */
class AttestChallengeCodecTest {

    private val secret = ByteArray(32) { it.toByte() }          // 00..1F（current）
    private val previous = ByteArray(32) { (it + 32).toByte() } // 20..3F（previous）
    private val projectId = "antique"
    private val issuedAtSec = 1_700_000_000L

    private fun codecAt(epochSecond: Long, secrets: List<ByteArray>) =
        AttestChallengeCodec(secrets, Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC))

    @Test
    fun `issued challenge verifies with same clock`() {
        val codec = codecAt(issuedAtSec, listOf(secret))
        val challenge = codec.issue(projectId)

        assertThat(challenge).hasSize(AttestChallengeCodec.CHALLENGE_STR_LEN)
        assertThat(codec.verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Valid)
    }

    @Test
    fun `output is always 76 chars of unpadded base64url`() {
        repeat(20) {
            val challenge = codecAt(issuedAtSec, listOf(secret)).issue(projectId)
            assertThat(challenge).hasSize(76)
            assertThat(challenge).doesNotContain("+", "/", "=")
            // 必须总能 base64url 解码回 57 字节
            assertThat(Base64.getUrlDecoder().decode(challenge)).hasSize(AttestChallengeCodec.TOTAL_LEN)
        }
    }

    @Test
    fun `tampering any byte fails with challenge_invalid`() {
        val codec = codecAt(issuedAtSec, listOf(secret))
        val decoded = Base64.getUrlDecoder().decode(codec.issue(projectId))

        // payload 区（issuedAt / random）与 MAC 区各取一点
        for (idx in intArrayOf(0, 5, 12, 25, 40, 56)) {
            val tampered = decoded.copyOf()
            tampered[idx] = (tampered[idx].toInt() xor 0x01).toByte()
            val tamperedStr = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered)
            assertThat(codec.verify(projectId, tamperedStr))
                .`as`("tampered byte index %d", idx)
                .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
        }
    }

    @Test
    fun `challenge bound to projectId - other project fails`() {
        val codec = codecAt(issuedAtSec, listOf(secret))
        val challenge = codec.issue(projectId)

        assertThat(codec.verify("other-project", challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
    }

    @Test
    fun `expires after 300s - boundary inclusive at 300`() {
        val challenge = codecAt(issuedAtSec, listOf(secret)).issue(projectId)

        // delta = +300s 仍在窗口内（now - issuedAt ∈ [-30s, +300s]）
        assertThat(codecAt(issuedAtSec + 300, listOf(secret)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Valid)
        // delta = +301s 过期
        assertThat(codecAt(issuedAtSec + 301, listOf(secret)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_EXPIRED))
        // 远超 → 过期
        assertThat(codecAt(issuedAtSec + 60_000, listOf(secret)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_EXPIRED))
    }

    @Test
    fun `clock skew boundary -30s accepted -31s rejected`() {
        val challenge = codecAt(issuedAtSec, listOf(secret)).issue(projectId)

        // 验证方时钟落后 30s（delta = -30s）→ 通过
        assertThat(codecAt(issuedAtSec - 30, listOf(secret)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Valid)
        // 落后 31s（delta = -31s，issuedAt 在未来太远）→ challenge_invalid
        assertThat(codecAt(issuedAtSec - 31, listOf(secret)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
    }

    @Test
    fun `challenge issued with previous secret still verifies`() {
        val challenge = codecAt(issuedAtSec, listOf(previous)).issue(projectId)

        // current,previous 依次尝试 → previous 签发的仍可校验
        assertThat(codecAt(issuedAtSec, listOf(secret, previous)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Valid)
        // 只配 current 的实例拒绝 previous 签发的 challenge
        assertThat(codecAt(issuedAtSec, listOf(secret)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
    }

    @Test
    fun `current secret takes precedence - current-signed verifies with rotation pair`() {
        val challenge = codecAt(issuedAtSec, listOf(secret)).issue(projectId)
        assertThat(codecAt(issuedAtSec, listOf(secret, previous)).verify(projectId, challenge))
            .isEqualTo(AttestChallengeCodec.Verification.Valid)
    }

    @Test
    fun `malformed input fails with challenge_invalid`() {
        val codec = codecAt(issuedAtSec, listOf(secret))

        // 非 base64url 字符
        assertThat(codec.verify(projectId, "****not-base64url****"))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
        // 长度不对
        assertThat(codec.verify(projectId, "AAAA"))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
        // 76 字符但内容全错（MAC 不匹配 / ver 不符）
        assertThat(codec.verify(projectId, "A".repeat(76)))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
        // 空
        assertThat(codec.verify(projectId, ""))
            .isEqualTo(AttestChallengeCodec.Verification.Invalid(AttestChallengeCodec.Reason.CHALLENGE_INVALID))
    }

    @Test
    fun `empty secret list is rejected at construction`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            AttestChallengeCodec(emptyList(), Clock.systemUTC())
        }
    }
}

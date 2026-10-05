package com.ifmix.core.job.attest

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * receipt 回填单测（规格 §7「core-job」行）：成功 / 已使用 / 退避三路的 DB 效果，
 * H2（PG 模式）真 SQL + [FakeAppleReceiptClient] 替身，不碰真网络。
 */
class ReceiptBackfillTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() = AttestRepoTestDb.setupTable(JdbcClient.create(AttestRepoTestDb.datasource()))
    }

    private val jdbc = JdbcClient.create(AttestRepoTestDb.datasource())
    private val repo = AttestationRepo(AttestRepoTestDb.datasource())
    private val config = testAttestJobConfig()
    private val now = Instant.parse("2026-10-04T00:00:00Z")

    @BeforeEach
    fun cleanDb() = AttestRepoTestDb.truncate(jdbc)

    @Test
    fun `成功_写 receipt + 清 attestation_object + next_refresh_at=+24h`() {
        val id = AttestRepoTestDb.insertBackfillCandidate(jdbc, keyId = "key-ok")
        val apple = FakeAppleReceiptClient { AppleReceiptClient.Result.Success("RCPT".toByteArray()) }
        val cleaner = ReceiptBackfillCleaner(repo, apple, config)

        val result = cleaner.runOnce(now)

        assertThat(result.success).isEqualTo(1)
        val snap = AttestRepoTestDb.snapshot(jdbc, id)
        assertThat(snap["receipt"] as ByteArray).isEqualTo("RCPT".toByteArray())
        assertThat(snap["attestation_object"]).isNull()
        assertThat(snap["next_refresh_at"]).isEqualTo(now.plus(24, ChronoUnit.HOURS))
        assertThat(snap["refresh_failure_count"]).isEqualTo(0)
        // attestation_object 原文以 base64 提交给 Apple
        assertThat(apple.calls.single().second).isEqualTo(
            Base64.getEncoder().encodeToString("raw-attest".toByteArray()),
        )
    }

    @Test
    fun `已使用4xx_清 attestation_object 且不再重试`() {
        val id = AttestRepoTestDb.insertBackfillCandidate(jdbc, keyId = "key-used")
        val apple = FakeAppleReceiptClient { AppleReceiptClient.Result.AlreadyUsed }
        val cleaner = ReceiptBackfillCleaner(repo, apple, config)

        val result = cleaner.runOnce(now)

        assertThat(result.alreadyUsed).isEqualTo(1)
        val snap = AttestRepoTestDb.snapshot(jdbc, id)
        assertThat(snap["attestation_object"]).isNull()
        assertThat(snap["receipt"]).isNull()
        // 放弃不重试：无退避写入，next_refresh_at 保持 NULL
        assertThat(snap["next_refresh_at"]).isNull()
        assertThat(snap["refresh_failure_count"]).isEqualTo(0)
        // 下一轮该 key 已不在候选集（attestation_object 已清）
        assertThat(repo.fetchBackfillRows(now, 200).filter { it.id == id }).isEmpty()
    }

    @Test
    fun `网络5xx429退避_failure+1 + next_refresh_at=now+base 保留attestation_object`() {
        val id = AttestRepoTestDb.insertBackfillCandidate(jdbc, keyId = "key-flaky")
        val apple = FakeAppleReceiptClient {
            AppleReceiptClient.Result.TransientError(429, "status=429")
        }
        val cleaner = ReceiptBackfillCleaner(repo, apple, config)

        val result = cleaner.runOnce(now)

        assertThat(result.backoff).isEqualTo(1)
        val snap = AttestRepoTestDb.snapshot(jdbc, id)
        assertThat(snap["attestation_object"] as ByteArray).isEqualTo("raw-attest".toByteArray())
        assertThat(snap["refresh_failure_count"]).isEqualTo(1)
        // 第 1 次失败（failureCount=0）→ 退避 = now + base(1h)
        assertThat(snap["next_refresh_at"]).isEqualTo(now.plus(1, ChronoUnit.HOURS))
    }

    @Test
    fun `连续失败_指数退避封顶24h`() {
        val id = AttestRepoTestDb.insertBackfillCandidate(jdbc, keyId = "key-retry")
        val apple = FakeAppleReceiptClient { AppleReceiptClient.Result.TransientError(503, "status=503") }
        val cleaner = ReceiptBackfillCleaner(repo, apple, config)

        // 模拟 10 轮：每轮 now 推进 48h（> 退避封顶 24h，保证退避行再次到期进入候选），
        // 验证每轮写入的 next_refresh_at = 该轮 now + min(cap, base*2^n)，n = 当前连续失败次数。
        // 预期退避小时数（base=1h）：r1(n=0)=1, r2(n=1)=1, r3=2, r4=4, r5=8, r6=16, r7..=24（封顶）。
        val expectedBackoffHours = listOf(1, 1, 2, 4, 8, 16, 24, 24, 24, 24)
        var now = Instant.parse("2026-10-04T00:00:00Z")
        expectedBackoffHours.forEachIndexed { round, hours ->
            val result = cleaner.runOnce(now)
            assertThat(result.backoff).isEqualTo(1)
            assertThat(AttestRepoTestDb.snapshot(jdbc, id)["next_refresh_at"])
                .isEqualTo(now.plus(hours.toLong(), ChronoUnit.HOURS))
            now = now.plus(48, ChronoUnit.HOURS)
        }
        // 10 轮后 refresh_failure_count = 10，attestation_object 一直保留
        val snap = AttestRepoTestDb.snapshot(jdbc, id)
        assertThat(snap["refresh_failure_count"]).isEqualTo(10)
        assertThat(snap["attestation_object"] as ByteArray).isEqualTo("raw-attest".toByteArray())
    }
}

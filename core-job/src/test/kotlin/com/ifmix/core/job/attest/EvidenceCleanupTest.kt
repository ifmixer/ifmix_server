package com.ifmix.core.job.attest

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * evidence 90 天清理单测（规格 §5.7 + §7「core-job」行）：
 * cutoff = now - retainDays 由 Kotlin 侧计算（可注入固定 now），早于 cutoff 的行被清，其余保留。
 */
class EvidenceCleanupTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() = AttestRepoTestDb.setupTable(JdbcClient.create(AttestRepoTestDb.datasource()))
    }

    private val jdbc = JdbcClient.create(AttestRepoTestDb.datasource())
    private val now = Instant.parse("2026-10-04T00:00:00Z")

    @BeforeEach
    fun cleanDb() = AttestRepoTestDb.truncate(jdbc)

    @Test
    fun `超90天evidence被清_未超期保留_evidence为NULL的行不受影响`() {
        val repo = AttestationRepo(AttestRepoTestDb.datasource())
        val cleaner = EvidenceCleanupCleaner(repo, testAttestJobConfig())

        val oldRow = AttestRepoTestDb.insertEvidenceRow(jdbc, createdAt = now.minus(91, ChronoUnit.DAYS))
        val freshRow = AttestRepoTestDb.insertEvidenceRow(jdbc, createdAt = now.minus(10, ChronoUnit.DAYS))
        val noEvidenceRow = AttestRepoTestDb.insertNoEvidenceRow(jdbc, createdAt = now.minus(200, ChronoUnit.DAYS))

        cleaner.runOnce(now)

        val oldSnap = AttestRepoTestDb.snapshot(jdbc, oldRow)
        assertThat(oldSnap["evidence"]).isNull()
        val freshSnap = AttestRepoTestDb.snapshot(jdbc, freshRow)
        assertThat(AttestationRepo.normalizeJsonBValue(freshSnap["evidence"] as String?))
            .isEqualTo("{\"verdict\":\"ok\"}")
        assertThat(AttestRepoTestDb.snapshot(jdbc, noEvidenceRow)["evidence"]).isNull()
    }

    @Test
    fun `边界_created_at正好now-90d保留_早一天才清`() {
        val repo = AttestationRepo(AttestRepoTestDb.datasource())
        val cleaner = EvidenceCleanupCleaner(repo, testAttestJobConfig())

        // created_at < now - 90d 才命中（严格小于）：正好 90d 的保留
        val exactly = AttestRepoTestDb.insertEvidenceRow(jdbc, createdAt = now.minus(90, ChronoUnit.DAYS))
        val oneDayOver = AttestRepoTestDb.insertEvidenceRow(jdbc, createdAt = now.minus(91, ChronoUnit.DAYS))

        cleaner.runOnce(now)

        assertThat(AttestRepoTestDb.snapshot(jdbc, exactly)["evidence"]).isNotNull()
        assertThat(AttestRepoTestDb.snapshot(jdbc, oneDayOver)["evidence"]).isNull()
    }
}

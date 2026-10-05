package com.ifmix.core.job.attest

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * fraud metric 刷新单测（规格 §7「core-job」行 + §2 决策 2/4）：
 * 成功写 fraud_metric=bit0*2+bit1 + next_refresh_at=+24h + failure 清零；429 退避；
 * deviceCheck 配置缺失 → 跳过 + 不写库（不报错）。H2 真 SQL + [FakeDeviceCheckClient]。
 */
class FraudMetricRefreshTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() = AttestRepoTestDb.setupTable(JdbcClient.create(AttestRepoTestDb.datasource()))
    }

    private val jdbc = JdbcClient.create(AttestRepoTestDb.datasource())
    private val repo = AttestationRepo(AttestRepoTestDb.datasource())
    private val config = testAttestJobConfig()
    private val now = Instant.parse("2026-10-04T00:00:00Z")

    private fun devIos(
        keyId: String? = "dc-key",
        p8: String? = generateTestP8KeyPem(),
        env: String? = "production",
        teamId: String? = "TEAM1",
    ) = AppAttestIosConfig(teamId = teamId, env = env, deviceCheckKeyId = keyId, deviceCheckPrivateKey = p8)

    @BeforeEach
    fun cleanDb() = AttestRepoTestDb.truncate(jdbc)

    @Test
    fun `成功_fraud_metric=bit0*2+bit1 + next_refresh_at=+24h + failure清零`() {
        AttestRepoTestDb.insertProjectConfig(jdbc, "p-metric", """{"ios":{"teamId":"TEAM1","env":"production","deviceCheckKeyId":"dc-key","deviceCheckPrivateKey":"dummy"}}""")
        val due = AttestRepoTestDb.insertRefreshCandidate(jdbc, projectId = "p-metric", nextRefreshAt = now.minus(1, ChronoUnit.HOURS))
        val deviceCheck = FakeDeviceCheckClient(DeviceCheckClient.CallResult.Success(DeviceCheckClient.Bits(bit0 = 1, bit1 = 1)))
        val cleaner = FraudMetricRefreshCleaner(repo, deviceCheck, config)

        val result = cleaner.runOnce(now)

        assertThat(result.refreshed).isEqualTo(1)
        val snap = AttestRepoTestDb.snapshot(jdbc, due)
        assertThat(snap["fraud_metric"]).isEqualTo(3)
        assertThat(snap["next_refresh_at"]).isEqualTo(now.plus(24, ChronoUnit.HOURS))
        assertThat(snap["refresh_failure_count"]).isEqualTo(0)
        // receipt 原文提交给 DeviceCheck（一期不轮换，receipt 列不动）
        assertThat(deviceCheck.calls.single().second as ByteArray).isEqualTo("receipt".toByteArray())
    }

    @Test
    fun `429退避_failure+1 + next_refresh_at=now+base 保留候选下一轮`() {
        AttestRepoTestDb.insertProjectConfig(jdbc, "p-429", """{"ios":{"teamId":"T","env":"production","deviceCheckKeyId":"k","deviceCheckPrivateKey":"p"}}""")
        val id = AttestRepoTestDb.insertRefreshCandidate(jdbc, projectId = "p-429", nextRefreshAt = now)
        val deviceCheck = FakeDeviceCheckClient(DeviceCheckClient.CallResult.TransientError(429, "status=429"))
        val cleaner = FraudMetricRefreshCleaner(repo, deviceCheck, config)

        val result = cleaner.runOnce(now)

        assertThat(result.backoff).isEqualTo(1)
        val snap = AttestRepoTestDb.snapshot(jdbc, id)
        assertThat(snap["fraud_metric"]).isNull()
        assertThat(snap["refresh_failure_count"]).isEqualTo(1)
        assertThat(snap["next_refresh_at"]).isEqualTo(now.plus(config.backoffBaseHours, ChronoUnit.HOURS))
    }

    @Test
    fun `deviceCheck配置缺失_跳过该行且不写库`() {
        // 只有 teamId/env，缺 deviceCheckKeyId / deviceCheckPrivateKey
        AttestRepoTestDb.insertProjectConfig(jdbc, "p-missing", """{"ios":{"teamId":"T","env":"production"}}""")
        val id = AttestRepoTestDb.insertRefreshCandidate(jdbc, projectId = "p-missing", nextRefreshAt = now)
        val deviceCheck = FakeDeviceCheckClient()
        val cleaner = FraudMetricRefreshCleaner(repo, deviceCheck, config)

        val result = cleaner.runOnce(now)

        assertThat(result.skipped).isEqualTo(1)
        assertThat(deviceCheck.calls).isEmpty() // 决策 4：缺失 → 根本不发起调用
        val snap = AttestRepoTestDb.snapshot(jdbc, id)
        assertThat(snap["fraud_metric"]).isNull()
        assertThat(snap["next_refresh_at"]).isEqualTo(now) // 保持原值，不推退避
        assertThat(snap["refresh_failure_count"]).isEqualTo(0)
    }

    @Test
    fun `project无配置行_同样跳过不报错`() {
        val id = AttestRepoTestDb.insertRefreshCandidate(jdbc, projectId = "p-no-row", nextRefreshAt = now)
        val deviceCheck = FakeDeviceCheckClient()
        val cleaner = FraudMetricRefreshCleaner(repo, deviceCheck, config)

        val result = cleaner.runOnce(now)

        assertThat(result.skipped).isEqualTo(1)
        assertThat(deviceCheck.calls).isEmpty()
    }
}

package com.ifmix.core.job.attest

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * evidence 90 天清理（规格 §5.7）：`UPDATE core_auth_install_attestation SET evidence = NULL
 * WHERE evidence IS NOT NULL AND created_at < now() - 90d`（每日一次，cron 触发）。
 *
 * cutoff = now - evidenceRetainDays 在 Kotlin 侧计算传入（SQL 不写 interval 字面量，单测可注入固定 now）。
 */
@Component
@EnableConfigurationProperties(AttestJobConfig::class)
class EvidenceCleanupCleaner(
    private val repo: AttestationRepo,
    private val config: AttestJobConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(now: Instant = Instant.now()) {
        val cutoff = now.minus(config.evidenceRetainDays, ChronoUnit.DAYS)
        val updated = repo.clearOldEvidence(cutoff)
        log.info("[attest-evidence] 完成. cleared={} cutoff={}", updated, cutoff)
    }
}

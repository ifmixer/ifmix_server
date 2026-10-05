package com.ifmix.core.job.attest

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * receipt 回填任务（规格 §5.8「iOS receipt 回填」+ §2 决策 1）：
 * 选取 `provider=110 AND status=10 AND receipt IS NULL AND attestation_object IS NOT NULL`，
 * 向 Apple `POST /v1/attestations`（`{key, attestation}`）换 receipt。
 *
 * 结果分三路：
 *  - Success → 写 receipt + next_refresh_at = now + 24h + 清 attestation_object；
 *  - AlreadyUsed（Apple 侧一次性消费）→ 清 attestation_object + 日志 reason=already_used，放弃不重试；
 *  - TransientError（网络/5xx/429）→ refresh_failure_count + 1、next_refresh_at = now + 指数退避（封顶 24h）。
 *
 * createInstall 全程不依赖 Apple（纯本地验证，core-api 侧）；Apple 故障只影响 fraud metric 的及时性。
 */
@Component
@EnableConfigurationProperties(AttestJobConfig::class)
class ReceiptBackfillCleaner(
    private val repo: AttestationRepo,
    private val apple: AppleReceiptClient,
    private val config: AttestJobConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 跑一轮回填。返回 {success, alreadyUsed, backoff}。 */
    fun runOnce(now: Instant = Instant.now()): RunResult {
        var success = 0
        var alreadyUsed = 0
        var backoff = 0
        // 分批 drain：候选 SQL 对退避行有 `next_refresh_at <= now` 守卫（AttestationRepo.fetchBackfillRows），
        // 退避行被推离候选集直到退避到期；Success / AlreadyUsed 行清空后离开候选集。空批终止。
        while (true) {
            val rows = repo.fetchBackfillRows(now, config.batchSize)
            if (rows.isEmpty()) break
            for (row in rows) {
                val attestationBase64 = java.util.Base64.getEncoder().encodeToString(row.attestationObject)
                when (val r = apple.exchangeReceipt(row.keyId, attestationBase64)) {
                    is AppleReceiptClient.Result.Success -> {
                        val n = repo.markBackfilled(row.id, r.receipt, now.plus(config.refreshIntervalHours, ChronoUnit.HOURS))
                        if (n > 0) success++ else log.warn("[attest-receipt-backfill] 行已不活跃，跳过. id={}", row.id)
                    }
                    is AppleReceiptClient.Result.AlreadyUsed -> {
                        log.info("event=attest.receipt_backfill reason=already_used projectId={} id={}", row.projectId, row.id)
                        if (repo.clearAttestationObject(row.id) > 0) alreadyUsed++
                    }
                    is AppleReceiptClient.Result.TransientError -> {
                        val backoffUntil = now.plus(backoffHours(row.failureCount), ChronoUnit.HOURS)
                        if (repo.markBackoff(row.id, backoffUntil) > 0) backoff++
                    }
                }
            }
        }
        val result = RunResult(success, alreadyUsed, backoff)
        log.info("[attest-receipt-backfill] 完成. {}", result)
        return result
    }

    /** 指数退避（小时）：min(cap, base * 2^n)，n = 连续失败次数。 */
    private fun backoffHours(n: Int): Long {
        if (n <= 0) return config.backoffBaseHours
        val exp = (n - 1).coerceAtMost(30)
        return minOf(config.backoffCapHours, config.backoffBaseHours shl exp)
    }

    data class RunResult(val success: Int, val alreadyUsed: Int, val backoff: Int)
}

package com.ifmix.core.job.attest

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * fraud metric 刷新任务（规格 §5.8「iOS fraud metric 刷新」+ §2 决策 2）：
 * 选取 `provider=110 AND status=10 AND receipt IS NOT NULL AND next_refresh_at <= now`，
 * 用 DeviceCheck JWT（ES256，按 ios.env 选 host）POST receipt 到 attest_data 端点。
 *
 * 响应是 `bit0/bit1/creationTimestamp`（**不含新 receipt、无「下次允许刷新」字段**）：
 *  - Success → fraud_metric = bit0*2 + bit1（0..3）、next_refresh_at = now + 24h（服务端策略）、
 *    refresh_failure_count = 0、receipt_expires_at 一期不写；
 *  - 429/5xx/网络 → refresh_failure_count + 1、next_refresh_at = now + 指数退避（封顶 24h）；
 *  - deviceCheck* 配置缺失 → 跳过该行 + 日志（不报错，§2 决策 4 / §4.1 口径）。
 * 一期只写入，不封禁。
 */
@Component
@EnableConfigurationProperties(AttestJobConfig::class)
class FraudMetricRefreshCleaner(
    private val repo: AttestationRepo,
    private val deviceCheck: DeviceCheckClient,
    private val config: AttestJobConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 跑一轮刷新。返回 {refreshed, skipped, backoff}。 */
    fun runOnce(now: Instant = Instant.now()): RunResult {
        var refreshed = 0
        var skipped = 0
        var backoff = 0
        for (row in repo.fetchRefreshRows(now, config.batchSize)) {
            val ios = repo.loadIosConfig(row.projectId)
            if (ios == null || ios.deviceCheckKeyId.isNullOrBlank() ||
                ios.deviceCheckPrivateKey.isNullOrBlank() || ios.env.isNullOrBlank()
            ) {
                // 决策 4：deviceCheck 配置缺失 → 跳过 + 日志（不报错）
                log.info("event=attest.fraud_metric_refresh reason=missing_config projectId={} id={}", row.projectId, row.id)
                skipped++
                continue
            }
            when (val r = deviceCheck.refresh(ios, row.receipt)) {
                is DeviceCheckClient.CallResult.Success -> {
                    val metric = r.bits.bit0 * 2 + r.bits.bit1
                    val n = repo.markFraudMetric(row.id, metric, now.plus(config.refreshIntervalHours, ChronoUnit.HOURS))
                    if (n > 0) {
                        refreshed++
                        log.info("event=attest.fraud_metric_refresh reason=ok projectId={} id={} metric={}", row.projectId, row.id, metric)
                    } else {
                        log.warn("[attest-refresh] 行已不活跃，跳过. id={}", row.id)
                    }
                }
                is DeviceCheckClient.CallResult.MissingConfig -> {
                    log.info("event=attest.fraud_metric_refresh reason=missing_config projectId={} id={}", row.projectId, row.id)
                    skipped++
                }
                is DeviceCheckClient.CallResult.TransientError -> {
                    val backoffUntil = now.plus(backoffHours(row.failureCount), ChronoUnit.HOURS)
                    if (repo.markRefreshBackoff(row.id, backoffUntil) > 0) backoff++
                }
            }
        }
        val result = RunResult(refreshed, skipped, backoff)
        log.info("[attest-refresh] 完成. {}", result)
        return result
    }

    private fun backoffHours(n: Int): Long {
        // 与回填共用同一指数策略（封顶 24h）。
        if (n <= 0) return config.backoffBaseHours
        val exp = (n - 1).coerceAtMost(30)
        return minOf(config.backoffCapHours, config.backoffBaseHours shl exp)
    }

    data class RunResult(val refreshed: Int, val skipped: Int, val backoff: Int)
}

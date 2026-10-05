package com.ifmix.core.job.attest

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * install attestation（WP-E）批处理任务配置。
 *
 * 调度由外部 Unix cron 负责（进程按 --job.name 跑完退出，见 JobDispatcher），本配置只含
 * 任务参数，不含 cron 字段。
 *
 * ```yaml
 * app:
 *   attest:
 *     batch-size: 200        # 单轮选取上限（ORDER BY created_at 分批 LIMIT 循环）
 *     backoff-base-hours: 1  # 指数退避基数（失败后 next_refresh_at = now + min(cap, base * 2^(n-1))）
 *     backoff-cap-hours: 24  # 退避封顶（规格 §5.8：指数退避封顶 24h）
 * ```
 */
@ConfigurationProperties(prefix = "app.attest")
data class AttestJobConfig(
    /** 单轮选取上限，分批 LIMIT 循环直到无候选。 */
    val batchSize: Int = 200,
    /** 退避基数（小时）：第 n 次失败后退避 min(cap, base * 2^(n-1))。 */
    val backoffBaseHours: Long = 1,
    /** 退避封顶（小时），规格 §5.8。 */
    val backoffCapHours: Long = 24,
    /** receipt 成功回填 / fraud metric 刷新成功后的刷新间隔（小时），规格 §5.8：now + 24h。 */
    val refreshIntervalHours: Long = 24,
    /** evidence 保留天数（规格 §5.7：90 天后清空）。 */
    val evidenceRetainDays: Long = 90,
)

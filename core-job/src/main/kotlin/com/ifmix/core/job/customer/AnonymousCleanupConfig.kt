package com.ifmix.core.job.customer

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 匿名 Customer 清理任务配置。
 *
 * 调度由外部 Unix cron 负责（进程按 --job.name 跑完退出），本配置不再含 cron 字段。
 *
 * ```yaml
 * app:
 *   customer:
 *     cleanup:
 *       batch-size: 500            # 每批处理条数（分批 LIMIT 循环）
 *       tombstone-window-days: 7   # 已合并 tombstone 审计窗口（合并超过此天数才删）
 * ```
 */
@ConfigurationProperties(prefix = "app.customer.cleanup")
data class AnonymousCleanupConfig(
    /** 每批处理条数，分批 LIMIT 循环直到无候选。 */
    val batchSize: Int = 500,
    /** 已合并 tombstone 审计窗口天数：合并时间（updatedAt）早于 now-此天数 才回收。 */
    val tombstoneWindowDays: Long = 7,
)

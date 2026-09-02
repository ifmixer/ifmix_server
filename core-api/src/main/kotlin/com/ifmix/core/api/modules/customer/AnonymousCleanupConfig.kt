package com.ifmix.core.api.modules.customer

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 阶段 6 匿名 Customer 清理任务配置。
 *
 * ```yaml
 * app:
 *   customer:
 *     cleanup:
 *       cron: "0 30 3 * * *"      # 每天 03:30 一次
 *       batch-size: 500            # 每批处理条数（分批 LIMIT 循环）
 *       tombstone-window-days: 7   # 已合并 tombstone 审计窗口（合并超过此天数才删）
 * ```
 */
@ConfigurationProperties(prefix = "app.customer.cleanup")
data class AnonymousCleanupConfig(
    /** Spring cron 表达式（6 段：秒 分 时 日 月 周）。默认每天 03:30。 */
    val cron: String = "0 30 3 * * *",
    /** 每批处理条数，分批 LIMIT 循环直到无候选。 */
    val batchSize: Int = 500,
    /** 已合并 tombstone 审计窗口天数：合并时间（updatedAt）早于 now-此天数 才回收。 */
    val tombstoneWindowDays: Long = 7,
)

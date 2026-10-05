package com.ifmix.core.job.attest

import org.springframework.context.annotation.Configuration

/**
 * install attestation（WP-E）三个批处理 Job（名即 --job.name，外部 cron 触发，见 [com.ifmix.core.job.JobDispatcher]）。
 *
 * 均单 tasklet step，业务在 cleaner 内部（JdbcClient + 外部 HTTP），Batch 元数据走 jobTransactionManager
 * （照抄 [com.ifmix.core.job.customer.AnonymousCleanupJob] 模式）：
 *
 *  - `attestReceiptBackfill`：[ReceiptBackfillCleaner] —— iOS attestation_object 换 receipt（§5.8）。
 *  - `attestFraudMetricRefresh`：[FraudMetricRefreshCleaner] —— receipt 换 DeviceCheck two bits（§5.8 / §2 决策 2）。
 *  - `attestEvidenceCleanup`：[EvidenceCleanupCleaner] —— evidence 90 天清理（§5.7）。
 *
 * **【2026-10-05 运维决定】线上暂不调度这三个 job**（不影响 createInstall/recover/attestExisting ——
 * 它们纯本地验证，不依赖 receipt/fraud_metric；代价仅 fraud metric 缺失 + attestation_object 列增长，
 * 见规格 §5.8 与计划文档 T6 备注）。**执行入口已注释**：三个 @Bean 不再注册，`--job.name=attest*` 会以
 * 退出码 2（未知 name）拒绝。Cleaner 组件保留且可单测。恢复调度：取消下方注释、重新部署，并在外部
 * cron 配回三个 --job.name；首次启用会回填全部历史 attestation_object，注意对 Apple 的集中请求量。
 */
@Configuration(proxyBeanMethods = false)
class AttestJobs {

    /*
    // 【入口注释 2026-10-05】线上暂不调度；恢复 = 取消注释整个方法（三个都要恢复才完整）。
    @Bean
    fun attestReceiptBackfillJob(
        jobRepository: JobRepository,
        @Qualifier("jobTransactionManager") jobTxManager: PlatformTransactionManager,
        cleaner: ReceiptBackfillCleaner,
    ): Job {
        val tasklet = Tasklet { _, _ ->
            cleaner.runOnce()
            RepeatStatus.FINISHED
        }
        val step = StepBuilder("attestReceiptBackfillStep", jobRepository).tasklet(tasklet, jobTxManager).build()
        return JobBuilder("attestReceiptBackfill", jobRepository).start(step).build()
    }

    @Bean
    fun attestFraudMetricRefreshJob(
        jobRepository: JobRepository,
        @Qualifier("jobTransactionManager") jobTxManager: PlatformTransactionManager,
        cleaner: FraudMetricRefreshCleaner,
    ): Job {
        val tasklet = Tasklet { _, _ ->
            cleaner.runOnce()
            RepeatStatus.FINISHED
        }
        val step = StepBuilder("attestFraudMetricRefreshStep", jobRepository).tasklet(tasklet, jobTxManager).build()
        return JobBuilder("attestFraudMetricRefresh", jobRepository).start(step).build()
    }

    @Bean
    fun attestEvidenceCleanupJob(
        jobRepository: JobRepository,
        @Qualifier("jobTransactionManager") jobTxManager: PlatformTransactionManager,
        cleaner: EvidenceCleanupCleaner,
    ): Job {
        val tasklet = Tasklet { _, _ ->
            cleaner.runOnce()
            RepeatStatus.FINISHED
        }
        val step = StepBuilder("attestEvidenceCleanupStep", jobRepository).tasklet(tasklet, jobTxManager).build()
        return JobBuilder("attestEvidenceCleanup", jobRepository).start(step).build()
    }
    */
}

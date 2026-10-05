package com.ifmix.core.job.attest

import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * install attestation（WP-E）三个批处理 Job（名即 --job.name，外部 cron 触发，见 [com.ifmix.core.job.JobDispatcher]）。
 *
 * 均单 tasklet step，业务在 cleaner 内部（JdbcClient + 外部 HTTP），Batch 元数据走 jobTransactionManager
 * （照抄 [com.ifmix.core.job.customer.AnonymousCleanupJob] 模式）：
 *
 *  - `attestReceiptBackfill`：[ReceiptBackfillCleaner] —— iOS attestation_object 换 receipt（§5.8）。
 *  - `attestFraudMetricRefresh`：[FraudMetricRefreshCleaner] —— receipt 换 DeviceCheck two bits（§5.8 / §2 决策 2）。
 *  - `attestEvidenceCleanup`：[EvidenceCleanupCleaner] —— evidence 90 天清理（§5.7）。
 */
@Configuration(proxyBeanMethods = false)
class AttestJobs {

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
}

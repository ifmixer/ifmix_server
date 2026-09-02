package com.ifmix.core.job.customer

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
 * 匿名 Customer 清理 Spring Batch Job（名 "anonymousCleanup"）。
 *
 * 单 tasklet step：调 [AnonymousCleanupCleaner.runOnce]（业务库 core_api_local 上的清理）
 * 后返回 [RepeatStatus.FINISHED]。执行历史/断点由 JobRepository 记于 Batch 元数据库 core_job_local。
 *
 * StepBuilder 用 jobTransactionManager（job 库事务）管理 Batch 元数据事务；
 * 真正的业务删除事务在 cleaner 内部用 businessTransactionManager 各自开启，两者互不影响。
 */
@Configuration(proxyBeanMethods = false)
class AnonymousCleanupJob {

    companion object {
        const val JOB_NAME = "anonymousCleanup"
    }

    @Bean
    fun anonymousCleanupJobDefinition(jobRepository: JobRepository, anonymousCleanupStep: Step): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .start(anonymousCleanupStep)
            .build()

    @Bean
    fun anonymousCleanupStep(
        jobRepository: JobRepository,
        @Qualifier("jobTransactionManager") jobTxManager: PlatformTransactionManager,
        cleaner: AnonymousCleanupCleaner,
    ): Step {
        val tasklet = Tasklet { _, _ ->
            cleaner.runOnce()
            RepeatStatus.FINISHED
        }
        return StepBuilder("anonymousCleanupStep", jobRepository)
            .tasklet(tasklet, jobTxManager)
            .build()
    }
}

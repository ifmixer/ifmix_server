package com.ifmix.core.job

import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.stereotype.Component

/**
 * Job 分发器：Unix cron 外部触发进程后，按 `--job.name` 跑指定 Spring Batch Job，跑完带退出码退出。
 *
 * 退出码约定（供 cron/脚本判定）：
 *  - 0：Job 运行且 COMPLETED。
 *  - 1：Job 运行但 BatchStatus != COMPLETED（失败）。
 *  - 2：入参错误（缺 --job.name 或未知 name）。
 *
 * 参数每次带变化的 run.id=时间戳，避免 Spring Batch「同参数 JobInstance 已完成」拒跑。
 */
@Component
class JobDispatcher(
    @param:Value("\${job.name:}") private val jobName: String,
    private val jobOperator: JobOperator,
    private val jobs: Map<String, Job>,
) : ApplicationRunner, ExitCodeGenerator {

    private val log = LoggerFactory.getLogger(javaClass)
    private var exitCode = 0

    override fun run(args: ApplicationArguments) {
        val name = jobName.trim()
        if (name.isEmpty()) {
            log.error("[dispatch] 缺少 --job.name 参数，无法分发。用法：--job.name=anonymousCleanup")
            exitCode = 2
            return
        }

        val job = jobs.values.firstOrNull { it.name == name }
        if (job == null) {
            log.error("[dispatch] 未知 job.name='{}'。可用：{}", name, jobs.values.map { it.name })
            exitCode = 2
            return
        }

        val params = JobParametersBuilder()
            .addLong("run.id", System.currentTimeMillis())
            .toJobParameters()

        log.info("[dispatch] 启动 Job '{}' params={}", name, params)
        val execution = jobOperator.start(job, params)
        val status = execution.status
        log.info("[dispatch] Job '{}' 结束 status={} exitStatus={}", name, status, execution.exitStatus.exitCode)

        if (status != BatchStatus.COMPLETED) {
            log.error("[dispatch] Job '{}' 未成功完成 status={}", name, status)
            exitCode = 1
        }
    }

    override fun getExitCode(): Int = exitCode
}

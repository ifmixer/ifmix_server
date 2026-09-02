package com.ifmix.core.job

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

/**
 * core-job：独立跑批进程（单实例部署，Unix cron 外部触发）。
 *
 * 进程启动 → [JobDispatcher] 按 `--job.name` 跑指定 Spring Batch Job → 跑完带退出码退出（非常驻）。
 * 不用 @Scheduled；调度交给外部 cron（见 scripts/run-job.sh）。
 * 非 web 进程（application.yml 设 spring.main.web-application-type=none）。
 * 双数据源：业务库 core_api_local（清理目标） + Batch 元数据库 core_job_local。
 */
@SpringBootApplication
class JobApplication

fun main(args: Array<String>) {
    // SpringApplication.exit 收集 ExitCodeGenerator（JobDispatcher）的退出码；
    // exitProcess 保证进程带该码退出，供 cron/脚本判定成败。
    exitProcess(SpringApplication.exit(runApplication<JobApplication>(*args)))
}

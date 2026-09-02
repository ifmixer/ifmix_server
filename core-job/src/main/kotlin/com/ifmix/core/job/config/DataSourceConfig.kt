package com.ifmix.core.job.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * 双数据源装配（最关键最易错处）。
 *
 * 两库职责严格隔离：
 *  - [businessDataSource]（@Primary，绑定 `app.datasource.business` → core_api_local）：
 *    清理任务读写 customer/subscription 等业务表。cleaner 注入它构 JdbcClient/TransactionTemplate。
 *  - [jobDataSource]（绑定 `app.datasource.job` → core_job_local）：
 *    仅承载 Spring Batch JobRepository 元数据（BATCH_JOB_*，执行历史/断点）。
 *
 * Spring Batch 通过 [BatchConfig]（继承 DefaultBatchConfiguration 重写 getDataSource()）强制走
 * jobDataSource；Batch 元数据表由 [batchSchemaInitializer] 用 Batch 官方 DDL 建到 core_job_local。
 *
 * 说明：Boot 4 / Batch 6 已移除 `spring.batch.jdbc.initialize-schema` 自动建表能力，
 * 故此处显式跑 schema-postgresql.sql，且严格绑定到 jobDataSource —— 绝不落到业务库。
 */
@Configuration(proxyBeanMethods = false)
class DataSourceConfig {

    /** 业务库（清理目标）。@Primary：默认注入点。 */
    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.business")
    fun businessDataSource(): HikariDataSource =
        HikariDataSource().also { it.poolName = "business-pool" }

    /** 业务库事务管理器：清理 cleaner 的 TransactionTemplate 用它（同事务先资源后主体删除）。 */
    @Bean
    @Primary
    fun businessTransactionManager(
        @Qualifier("businessDataSource") businessDataSource: DataSource,
    ): PlatformTransactionManager = DataSourceTransactionManager(businessDataSource)

    /** Batch 元数据库。名字固定 jobDataSource，供 [BatchConfig] 与 schema 初始化取用。 */
    @Bean
    @ConfigurationProperties("app.datasource.job")
    fun jobDataSource(): HikariDataSource =
        HikariDataSource().also { it.poolName = "job-pool" }

    /** job 库事务管理器：Spring Batch StepBuilder / JobRepository 用它管理 Batch 元数据事务。 */
    @Bean
    fun jobTransactionManager(
        @Qualifier("jobDataSource") jobDataSource: DataSource,
    ): PlatformTransactionManager = DataSourceTransactionManager(jobDataSource)

    /**
     * Batch 元数据 schema 初始化：仅对 [jobDataSource]（core_job_local）跑 Batch 官方 postgres DDL。
     * 幂等（Batch 建表脚本 CREATE TABLE，重复跑已存在会报错 → 用 continueOnError 容忍已建表）。
     */
    @Bean
    fun batchSchemaInitializer(
        @Qualifier("jobDataSource") jobDataSource: DataSource,
    ): DataSourceScriptDatabaseInitializer {
        val settings = DatabaseInitializationSettings().apply {
            schemaLocations = listOf("classpath:org/springframework/batch/core/schema-postgresql.sql")
            mode = DatabaseInitializationMode.ALWAYS
            isContinueOnError = true
        }
        return DataSourceScriptDatabaseInitializer(jobDataSource, settings)
    }
}

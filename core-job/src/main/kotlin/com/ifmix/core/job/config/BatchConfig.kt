package com.ifmix.core.job.config

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * Spring Batch 元数据装配：强制 JobRepository / JobOperator 使用 [jobDataSource]（core_job_local）。
 *
 * Batch 6 的 JDBC 基类是 [JdbcDefaultBatchConfiguration]（继承 DefaultBatchConfiguration），
 * 暴露 getDataSource()/getTransactionManager() 供覆盖。重写它们指向 job 库，
 * 覆盖 Boot 默认「用 @Primary 数据源」的行为 —— 否则 Batch 元数据会误建到业务库 core_api_local。
 *
 * 本类是 DefaultBatchConfiguration 子类，满足 Boot BatchAutoConfiguration 的
 * @ConditionalOnMissingBean(DefaultBatchConfiguration) —— 从而禁用 Boot 的默认 Batch 配置。
 */
@Configuration(proxyBeanMethods = false)
class BatchConfig(
    @param:Qualifier("jobDataSource") private val jobDataSource: DataSource,
    @param:Qualifier("jobTransactionManager") private val jobTxManager: PlatformTransactionManager,
) : JdbcDefaultBatchConfiguration() {

    override fun getDataSource(): DataSource = jobDataSource

    override fun getTransactionManager(): PlatformTransactionManager = jobTxManager
}

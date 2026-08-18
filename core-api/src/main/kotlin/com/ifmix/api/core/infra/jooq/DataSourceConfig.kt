package com.ifmix.api.core.infra.jooq

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

/**
 * 数据源配置：单集群模式，一个 writer + 一个 reader。
 * 读写分离由 ReadWriteRoutingDataSource + Spring @Transactional(readOnly) 自动处理。
 */
@ConfigurationProperties(prefix = "app.datasource")
data class ClusterProperties(
    val writer: DataSourceProps = DataSourceProps(),
    val reader: DataSourceProps = DataSourceProps(),
) {
    data class DataSourceProps(
        val jdbcUrl: String = "",
        val username: String = "",
        val password: String = "",
        val maximumPoolSize: Int = 10,
    )
}

/**
 * 读写分离路由。
 * - 非只读事务 → writer
 * - @Transactional(readOnly=true) → reader
 */
@Component
class ReadWriteRoutingDataSource(
    private val writerDs: DataSource,
    private val readerDs: DataSource,
) : org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource() {

    companion object {
        private const val WRITER = "writer"
        private const val READER = "reader"
    }

    init {
        setTargetDataSources(mapOf<Any, Any>(WRITER to writerDs, READER to readerDs))
        setDefaultTargetDataSource(writerDs)
        afterPropertiesSet()
    }

    override fun determineCurrentLookupKey(): Any {
        val isReadOnly = org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        return if (isReadOnly) READER else WRITER
    }
}

/**
 * 数据源注册：创建 writer/reader HikariDataSource 并提供路由 DataSource。
 */
@Component
class DataSourceRegistry(
    private val props: ClusterProperties,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    val writerDataSource: HikariDataSource by lazy { createDataSource(props.writer, "pg-writer") }
    val readerDataSource: HikariDataSource by lazy { createDataSource(props.reader, "pg-reader") }
    val routingDataSource: ReadWriteRoutingDataSource by lazy { ReadWriteRoutingDataSource(writerDataSource, readerDataSource) }

    @PreDestroy
    fun destroy() {
        writerDataSource.close()
        readerDataSource.close()
    }

    private fun createDataSource(props: ClusterProperties.DataSourceProps, poolName: String): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = props.jdbcUrl
            username = props.username
            password = props.password
            maximumPoolSize = props.maximumPoolSize
            this.poolName = poolName
        }
        return HikariDataSource(config)
    }
}

@Configuration
@EnableTransactionManagement
class JdbcConfig(
    private val dataSourceRegistry: DataSourceRegistry,
) {
    /** 路由 DataSource 注册为 Spring bean，供 TransactionManager 使用 */
    @Bean
    @Primary
    fun dataSource(): DataSource = dataSourceRegistry.routingDataSource

    /** Spring 声明式事务管理器，绑定到路由 DataSource */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)
}

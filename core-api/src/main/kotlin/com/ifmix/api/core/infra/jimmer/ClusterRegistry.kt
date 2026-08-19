package com.ifmix.api.core.infra.jimmer

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PreDestroy
import org.babyfish.jimmer.sql.DraftInterceptor
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.Executor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * 单集群数据源注册：一个 writer + 一个 reader。
 *
 * 提供：
 * - [sqlClient]：KSqlClient 实例，通过 Spring 管理的 DataSource 获取连接
 * - [routingDataSource]：用于注册 Spring TransactionManager
 * - [writerDataSource]：用于 Flyway migration
 */
@Component
class ClusterRegistry(
    private val props: ClusterProperties,
    private val draftInterceptors: List<DraftInterceptor<*, *>>,
    @Value("${app.show-sql:false}") private val showSql: Boolean,
) {
    private val log = LoggerFactory.getLogger(ClusterRegistry::class.java)
    val writerDataSource: HikariDataSource by lazy { createDataSource(props.writer, "pg-writer") }
    val readerDataSource: HikariDataSource by lazy { createDataSource(props.reader, "pg-reader") }
    val routingDataSource: ReadWriteRoutingDataSource by lazy { ReadWriteRoutingDataSource(writerDataSource, readerDataSource) }

    /**
     * 全局唯一的 KSqlClient。
     * ConnectionManager 从 routingDataSource 获取连接，由 Spring TX 控制读写路由。
     */
    val sqlClient: KSqlClient by lazy {
        newKSqlClient {
            setConnectionManager {
                val conn = routingDataSource.connection
                try {
                    proceed(conn)
                } finally {
                    conn.close()
                }
            }
            setDialect(PostgresDialect())
            if (showSql) {
                setExecutor(Executor.log())
                setExecutorContextPrefixes(listOf("com.ifmix.api.core"))
            }
            for (interceptor in draftInterceptors) {
                addDraftInterceptor(interceptor)
            }
        }
    }

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

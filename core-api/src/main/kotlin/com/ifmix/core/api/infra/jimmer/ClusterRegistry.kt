package com.ifmix.core.api.infra.jimmer

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
 * - [writerSql] / [readerSql]：独立的 KSqlClient 实例，分别绑定 writer/reader DataSource
 * - [routingDataSource]：路由 DataSource，供 Spring TransactionManager 使用
 * - [writerDataSource]：用于 Flyway migration
 */
@Component
class ClusterRegistry(
    private val props: ClusterProperties,
    private val draftInterceptors: List<DraftInterceptor<*, *>>,
    @Value("\${app.show-sql:false}") private val showSql: Boolean,
) {
    private val log = LoggerFactory.getLogger(ClusterRegistry::class.java)
    val writerDataSource: HikariDataSource by lazy { createDataSource(props.writer, "pg-writer") }
    val readerDataSource: HikariDataSource by lazy { createDataSource(props.reader, "pg-reader") }
    val routingDataSource: ReadWriteRoutingDataSource by lazy { ReadWriteRoutingDataSource(writerDataSource, readerDataSource) }

    /** writer KSqlClient — 用于写操作和全局事务 */
    val writerSql: KSqlClient by lazy { createSqlClient(writerDataSource) }

    /** reader KSqlClient — 用于读操作 */
    val readerSql: KSqlClient by lazy { createSqlClient(readerDataSource) }

    /** 向后兼容：返回 writerSql，供仍注入单一 sqlClient bean 的老代码使用 */
    val sqlClient: KSqlClient get() = writerSql

    @PreDestroy
    fun destroy() {
        writerDataSource.close()
        readerDataSource.close()
    }

    private fun createSqlClient(dataSource: DataSource): KSqlClient = newKSqlClient {
        setConnectionManager {
            val conn = dataSource.connection
            try {
                proceed(conn)
            } finally {
                conn.close()
            }
        }
        setDialect(PostgresDialect())
        if (showSql) {
            setExecutor(Executor.log())
            setExecutorContextPrefixes(listOf("com.ifmix.core.api"))
        }
        for (interceptor in draftInterceptors) {
            addDraftInterceptor(interceptor)
        }
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

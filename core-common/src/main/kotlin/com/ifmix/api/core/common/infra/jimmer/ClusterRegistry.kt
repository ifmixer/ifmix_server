package com.ifmix.api.core.common.infra.jimmer

import com.ifmix.api.core.common.infra.db.RepoContext
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PreDestroy
import org.babyfish.jimmer.sql.DraftInterceptor
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.Executor
import org.springframework.stereotype.Component
import java.sql.Connection

/**
 * 多集群数据源注册：每个集群持有独立的 writer + reader 两个 KSqlClient。
 *
 * 提供：
 * - [sql(ctx)]：根据 RepoContext 选择对应集群和读写节点
 * - [defaultCluster]：默认集群
 * - [flywayDataSource]：Flyway migration 使用的 writer DataSource
 */
@Component
class ClusterRegistry(
    private val props: AppDataSourceProperties,
    private val draftInterceptors: List<DraftInterceptor<*, *>>,
) {
    private val clusters: Map<String, Cluster> by lazy {
        props.clusters.mapValues { (id, clusterProps) ->
            val writerDs = createDataSource(clusterProps.writer, "pg-$id-writer")
            val readerDs = createDataSource(clusterProps.reader, "pg-$id-reader")
            Cluster(
                id = id,
                writerDataSource = writerDs,
                readerDataSource = readerDs,
                writerClient = buildClient(writerDs, autoCommit = false),
                readerClient = buildClient(readerDs, autoCommit = true),
            )
        }
    }

    fun getCluster(clusterId: String): Cluster =
        clusters[clusterId]
            ?: clusters[props.routing.defaultCluster]
            ?: error("Cluster not found: $clusterId")

    fun sql(ctx: RepoContext): KSqlClient =
        getCluster(ctx.clusterId).sql(ctx.preferReader)

    val defaultCluster: Cluster get() = getCluster(props.routing.defaultCluster)

    val flywayDataSource: HikariDataSource get() = defaultCluster.writerDataSource

    /** 向后兼容：默认集群 writer client，供 E2E 测试等直接使用 */
    val sqlClient: KSqlClient get() = defaultCluster.writerClient

    @PreDestroy
    fun destroy() {
        clusters.values.forEach { it.close() }
    }

    private fun buildClient(ds: HikariDataSource, autoCommit: Boolean): KSqlClient {
        return newKSqlClient {
            setConnectionManager {
                ds.connection.use { conn: Connection ->
                    if (autoCommit) conn.autoCommit = true
                    proceed(conn)
                }
            }
            setDialect(PostgresDialect())
            if (props.showSql) {
                setExecutor(Executor.log())
                setExecutorContextPrefixes(listOf("com.ifmix.api.core"))
            }
            for (interceptor in draftInterceptors) {
                addDraftInterceptor(interceptor)
            }
        }
    }

    private fun createDataSource(
        dsProps: AppDataSourceProperties.DataSourceProps,
        poolName: String,
    ): HikariDataSource {
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = dsProps.jdbcUrl
            username = dsProps.username
            password = dsProps.password
            maximumPoolSize = dsProps.maximumPoolSize
            this.poolName = poolName
        })
    }
}

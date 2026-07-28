package com.ifmix.api.core.common.jimmer.cluster

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class ClusterRegistry(
    private val props: ClusterProperties,
) {
    private lateinit var clients: Map<String, KSqlClient>
    private lateinit var dataSources: Map<String, Pair<HikariDataSource, HikariDataSource>>
    private lateinit var routingMap: Map<String, String> // appId → clusterName

    @PostConstruct
    fun init() {
        routingMap = props.clusterRouting.mappings

        val dsMap = mutableMapOf<String, Pair<HikariDataSource, HikariDataSource>>()
        val clientMap = mutableMapOf<String, KSqlClient>()

        props.clusters.forEach { (name, cluster) ->
            val writerDs = createDataSource(cluster.writer, "$name-writer")
            val readerDs = createDataSource(cluster.reader, "$name-reader")
            dsMap[name] = writerDs to readerDs

            val routingDs = ReadWriteRoutingDataSource(writerDs, readerDs)
            val sqlClient = newKSqlClient {
                setConnectionManager {
                    val con = routingDs.connection
                    try {
                        proceed(con)
                    } finally {
                        con.close()
                    }
                }
                setDialect(PostgresDialect())
            }
            clientMap[name] = sqlClient
        }

        dataSources = dsMap
        clients = clientMap
    }

    /** 按 appId 获取对应集群的 KSqlClient。未映射的走 default。 */
    fun forAppId(appId: String): KSqlClient {
        val clusterName = routingMap[appId] ?: "default"
        return clients[clusterName]
            ?: throw IllegalStateException("Cluster '$clusterName' not found (appId=$appId)")
    }

    /** 获取默认集群的 KSqlClient */
    fun primary(): KSqlClient =
        clients["default"] ?: throw IllegalStateException("No 'default' cluster configured")

    /** 获取所有集群的 writer DataSource（用于 Flyway） */
    fun allWriterDataSources(): Map<String, DataSource> =
        dataSources.mapValues { it.value.first }

    @PreDestroy
    fun destroy() {
        dataSources.values.forEach { (writer, reader) ->
            writer.close()
            reader.close()
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

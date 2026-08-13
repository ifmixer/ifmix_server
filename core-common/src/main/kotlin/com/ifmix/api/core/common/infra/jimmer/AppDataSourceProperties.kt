package com.ifmix.api.core.common.infra.jimmer

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppDataSourceProperties(
    val clusters: Map<String, ClusterProps> = mapOf("default" to ClusterProps()),
    val routing: RoutingProps = RoutingProps(),
    val showSql: Boolean = false,
) {
    data class ClusterProps(
        val writer: DataSourceProps = DataSourceProps(),
        val reader: DataSourceProps = DataSourceProps(),
    )
    data class DataSourceProps(
        val jdbcUrl: String = "",
        val username: String = "",
        val password: String = "",
        val maximumPoolSize: Int = 10,
    )
    data class RoutingProps(
        val defaultCluster: String = "default",
        val globalCluster: String = "default",
    )
}

package com.ifmix.api.core.common.jimmer.cluster

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class ClusterProperties(
    val clusters: Map<String, ClusterProps> = mapOf(),
    val clusterRouting: RoutingProps = RoutingProps(),
) {
    data class ClusterProps(
        val writer: DataSourceProps,
        val reader: DataSourceProps,
    )

    data class DataSourceProps(
        val jdbcUrl: String,
        val username: String = "",
        val password: String = "",
        val maximumPoolSize: Int = 10,
    )

    data class RoutingProps(
        val mappings: Map<String, String> = emptyMap(),
    )
}

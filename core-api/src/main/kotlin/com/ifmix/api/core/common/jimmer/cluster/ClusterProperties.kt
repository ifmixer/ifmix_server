package com.ifmix.api.core.common.jimmer.cluster

import org.springframework.boot.context.properties.ConfigurationProperties

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

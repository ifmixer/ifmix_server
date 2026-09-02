package com.ifmix.core.common.config

/**
 * 数据源配置结构：一个 writer + 一个 reader。
 *
 * 纯 data class，不带 Spring 注解——使用方（core-api / core-job）各自用
 * `@ConfigurationProperties(prefix = "app.datasource")` 绑定，保持 core-common 无 Spring 依赖。
 */
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

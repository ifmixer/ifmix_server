package com.ifmix.api.core.infra.jimmer

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement

@Configuration
@EnableConfigurationProperties(AppDataSourceProperties::class)
@EnableTransactionManagement
class JimmerConfig(private val clusterRegistry: ClusterRegistry) {

    @Bean
    fun transactionManager(): PlatformTransactionManager =
        DataSourceTransactionManager(clusterRegistry.defaultCluster.writerDataSource)
}

package com.ifmix.core.api.infra.jimmer

import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

/**
 * Jimmer + 数据源配置。
 *
 * 注册 Spring 管理的 DataSource 和 TransactionManager，使 @Transactional 生效。
 * ReadWriteRoutingDataSource 根据 @Transactional(readOnly=true) 自动路由到 reader。
 */
@Configuration
@EnableConfigurationProperties(ClusterProperties::class)
@EnableTransactionManagement
class JimmerConfig {

    /** 路由 DataSource 注册为 Spring bean，供 TransactionManager 使用 */
    @Bean
    fun dataSource(registry: ClusterRegistry): DataSource = registry.routingDataSource

    /** Spring 声明式事务管理器，绑定到路由 DataSource */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)

    /** Jimmer KSqlClient 注册为 Spring bean，全局单例 */
    @Bean
    fun sqlClient(registry: ClusterRegistry): KSqlClient = registry.sqlClient
}

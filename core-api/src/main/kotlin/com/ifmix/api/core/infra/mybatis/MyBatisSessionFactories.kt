package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.jooq.DataSourceRegistry
import org.apache.ibatis.mapping.Environment
import org.apache.ibatis.session.Configuration
import org.apache.ibatis.session.SqlSessionFactory
import org.apache.ibatis.session.SqlSessionFactoryBuilder
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * 双 SqlSessionFactory（writer / reader）。
 * 手动构建，不依赖 mybatis-spring auto-configuration，完全控制 DataSource 绑定。
 *
 * Mapper 注册由各业务模块通过 @PostConstruct 在 Configuration 上 addMapper。
 */
@Component
class MyBatisSessionFactories(
    private val registry: DataSourceRegistry,
) {

    val writer: SqlSessionFactory by lazy { build(registry.writerDataSource, "mybatis-writer") }
    val reader: SqlSessionFactory by lazy { build(registry.readerDataSource, "mybatis-reader") }

    private fun build(ds: DataSource, envId: String): SqlSessionFactory {
        val txFactory = JdbcTransactionFactory()
        val env = Environment(envId, txFactory, ds)
        val config = Configuration(env).apply {
            isMapUnderscoreToCamelCase = true
            typeHandlerRegistry.register(UuidTypeHandler::class.java)
            typeHandlerRegistry.register(InstantTypeHandler::class.java)
            typeHandlerRegistry.register(JsonbTypeHandler::class.java)
        }
        return SqlSessionFactoryBuilder().build(config)
    }
}

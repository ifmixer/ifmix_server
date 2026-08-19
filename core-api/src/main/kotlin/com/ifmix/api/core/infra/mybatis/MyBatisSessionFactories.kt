package com.ifmix.api.core.infra.mybatis

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean
import com.baomidou.mybatisplus.core.config.GlobalConfig
import com.baomidou.mybatisplus.core.MybatisConfiguration
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor
import com.ifmix.api.core.infra.jooq.DataSourceRegistry
import org.apache.ibatis.session.SqlSessionFactory
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * 双 SqlSessionFactory（writer / reader）— 基于 MyBatis Plus 增强版工厂。
 * 手动构建，不依赖 mybatis-plus auto-configuration，完全控制 DataSource 绑定。
 *
 * Mapper 注册由各业务模块通过 @PostConstruct 在 Configuration 上 addMapper。
 */
@Component
class MyBatisSessionFactories(
    private val registry: DataSourceRegistry,
    private val timeAutoFillHandler: TimeAutoFillHandler,
) {

    val writer: SqlSessionFactory by lazy { buildFactory(registry.writerDataSource) }
    val reader: SqlSessionFactory by lazy { buildFactory(registry.readerDataSource) }

    private fun buildFactory(ds: DataSource): SqlSessionFactory {
        val factory = MybatisSqlSessionFactoryBean()
        factory.setDataSource(ds)
        factory.addPlugins(mybatisPlusInterceptor())
        factory.setGlobalConfig(globalConfig())
        val config = MybatisConfiguration()
        config.isMapUnderscoreToCamelCase = true
        config.typeHandlerRegistry.register(UuidTypeHandler::class.java)
        config.typeHandlerRegistry.register(InstantTypeHandler::class.java)
        config.typeHandlerRegistry.register(MetaTypeHandler::class.java)
        factory.setConfiguration(config)
        return factory.getObject()!!
    }

    private fun mybatisPlusInterceptor(): MybatisPlusInterceptor = MybatisPlusInterceptor().apply {
        // 暂不添加 TenantLineInnerInterceptor（appId 显式传递）
        addInnerInterceptor(OptimisticLockerInnerInterceptor())
    }

    private fun globalConfig() = GlobalConfig().apply {
        dbConfig = GlobalConfig.DbConfig().apply {
            logicDeleteValue = "NULL"
            logicNotDeleteValue = "NOW()"
        }
        metaObjectHandler = timeAutoFillHandler
    }
}

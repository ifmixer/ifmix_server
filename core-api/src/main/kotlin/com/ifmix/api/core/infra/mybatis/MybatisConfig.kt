package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import org.apache.ibatis.session.SqlSessionFactory
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.annotation.MapperScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * MyBatis SqlSessionFactory 配置。
 *
 * Phase 0: 单一 factory 绑定 writer DataSource（所有 MyBatis 查询走 writer）。
 * Phase 2: 拆为 writerFactory + readerFactory，由 ModuleCtx 显式选择。
 */
@Configuration
@MapperScan("com.ifmix.api.core.modules.demo.repo.mybatis")
class MybatisConfig(private val registry: ClusterRegistry) {

    @Bean
    fun sqlSessionFactory(): SqlSessionFactory {
        val factory = SqlSessionFactoryBean()
        factory.setDataSource(registry.writerDataSource)
        factory.setConfiguration(org.apache.ibatis.session.Configuration().apply {
            isMapUnderscoreToCamelCase = true
        })
        return factory.getObject()!!
    }
}

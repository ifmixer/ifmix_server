package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.infra.mybatis.MyBatisSessionFactories
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoMapper
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemMapper
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration

/**
 * Demo 模块 MyBatis 配置 — 注册本模块的 Mapper 到 SqlSessionFactory。
 * Mapper 由 MyBatis Generator 生成（./gradlew :core-api:generateMybatis）。
 */
@Configuration
class DemoMyBatisConfig(private val factories: MyBatisSessionFactories) {

    @PostConstruct
    fun registerMappers() {
        listOf(factories.writer, factories.reader).forEach { sf ->
            sf.configuration.addMapper(CoreTodoMapper::class.java)
            sf.configuration.addMapper(CoreTodoItemMapper::class.java)
        }
    }
}

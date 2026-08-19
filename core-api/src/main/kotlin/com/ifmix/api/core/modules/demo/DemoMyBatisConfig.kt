package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.infra.mybatis.MyBatisSessionFactories
import com.ifmix.api.core.modules.demo.repo.TodoItemMapper
import com.ifmix.api.core.modules.demo.repo.TodoMapper
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration

/**
 * Demo 模块 MyBatis 配置 — 注册本模块的 Mapper 到 SqlSessionFactory。
 */
@Configuration
class DemoMyBatisConfig(private val factories: MyBatisSessionFactories) {

    @PostConstruct
    fun registerMappers() {
        listOf(factories.writer, factories.reader).forEach { sf ->
            sf.configuration.addMapper(TodoMapper::class.java)
            sf.configuration.addMapper(TodoItemMapper::class.java)
        }
    }
}

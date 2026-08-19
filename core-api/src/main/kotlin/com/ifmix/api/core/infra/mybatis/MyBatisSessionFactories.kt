package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.jooq.DataSourceRegistry
import com.ifmix.api.core.modules.demo.repo.TodoItemMapper
import com.ifmix.api.core.modules.demo.repo.TodoMapper
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
 * Mapper 注册（TodoMapper / TodoItemMapper）在 Phase 2 加入后启用。
 */
@Component
class MyBatisSessionFactories(
    private val registry: DataSourceRegistry,
) {

    val writer: SqlSessionFactory by lazy { build(registry.writerDataSource) }
    val reader: SqlSessionFactory by lazy { build(registry.readerDataSource) }

    private fun build(ds: DataSource): SqlSessionFactory {
        val txFactory = JdbcTransactionFactory()
        val env = Environment("mybatis", txFactory, ds)
        val config = Configuration(env).apply {
            // mapUnderscoreToCamelCase 默认 true，无需显式设置
            typeHandlerRegistry.register(UuidTypeHandler::class.java)
            typeHandlerRegistry.register(InstantTypeHandler::class.java)
            typeHandlerRegistry.register(JsonbTypeHandler::class.java)
            addMapper(TodoMapper::class.java)
            addMapper(TodoItemMapper::class.java)
        }
        return SqlSessionFactoryBuilder().build(config)
    }
}

package com.ifmix.api.core.support

import com.ifmix.api.core.infra.jimmer.ClusterProperties
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Jimmer + PostgreSQL 集成测试基类。
 * 使用 Testcontainers 启动 PG，Flyway 自动建表。
 */
@Testcontainers
abstract class AbstractJimmerTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ifmix_test")
            .withUsername("test")
            .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.clusters.default.writer.jdbc-url") { postgres.jdbcUrl }
            registry.add("app.clusters.default.writer.username") { postgres.username }
            registry.add("app.clusters.default.writer.password") { postgres.password }
            registry.add("app.clusters.default.reader.jdbc-url") { postgres.jdbcUrl }
            registry.add("app.clusters.default.reader.username") { postgres.username }
            registry.add("app.clusters.default.reader.password") { postgres.password }
        }
    }

    protected fun createTestRegistry(): ClusterRegistry {
        val ds = ClusterProperties.DataSourceProps(
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
        )
        val props = ClusterProperties(
            clusters = mapOf("default" to ClusterProperties.ClusterProps(writer = ds, reader = ds)),
        )
        val registry = ClusterRegistry(props)
        registry.init()
        return registry
    }

    @AfterEach
    fun tearDown() {
        // Cleanup if needed
    }
}

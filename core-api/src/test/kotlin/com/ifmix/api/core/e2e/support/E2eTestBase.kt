package com.ifmix.api.core.e2e.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * E2E 测试基类。
 *
 * 自动启动 PostgreSQL + Redis 容器，Spring Boot 以 RANDOM_PORT 启动。
 * Flyway 自动执行所有 migration（含 V8 core_ 前缀）。
 *
 * 用法：继承此类，用 webClient 发真实 HTTP 请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
abstract class E2eTestBase {

    lateinit var webClient: WebTestClient

    @LocalServerPort
    var port: Int = 0

    @BeforeEach
    fun initWebClient() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    companion object {
        /** 测试用 appId */
        const val TEST_APP_ID = "00000000-0000-0000-0000-000000000001"
        const val TEST_INSTALL_ID = "00000000-0000-0000-0000-000000000099"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ifmix_test")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.datasource.writer.jdbc-url") { postgres.jdbcUrl }
            registry.add("app.datasource.writer.username") { postgres.username }
            registry.add("app.datasource.writer.password") { postgres.password }
            registry.add("app.datasource.reader.jdbc-url") { postgres.jdbcUrl }
            registry.add("app.datasource.reader.username") { postgres.username }
            registry.add("app.datasource.reader.password") { postgres.password }
            registry.add("spring.data.redis.url") {
                "redis://${redis.host}:${redis.getMappedPort(6379)}"
            }
            registry.add("app.storage.type") { "none" }
            registry.add("spring.ai.openai.api-key") { "sk-test" }
        }
    }

    /** PUT 请求 with appId header */
    protected fun put(path: String, body: Any? = null) = webClient.put()
        .uri(path)
        .header("x-app-id", TEST_APP_ID)
        .header("x-install-id", TEST_INSTALL_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .apply { if (body != null) bodyValue(body) }

    /** POST 请求 with appId header */
    protected fun post(path: String, body: Any? = null) = webClient.post()
        .uri(path)
        .header("x-app-id", TEST_APP_ID)
        .header("x-install-id", TEST_INSTALL_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .apply { if (body != null) bodyValue(body) }
}

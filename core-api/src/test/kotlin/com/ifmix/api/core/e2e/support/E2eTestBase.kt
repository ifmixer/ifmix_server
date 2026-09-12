package com.ifmix.core.api.e2e.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
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

/**
 * E2E 测试基类。
 *
 * 使用 Singleton Container Pattern：PostgreSQL + Redis 容器在整个 JVM 生命周期内共享。
 * Flyway 自动执行所有 migration（含 V8 core_ 前缀）。
 *
 * 用法：继承此类，用 webClient 发真实 HTTP 请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        /** 测试用 projectId */
        const val TEST_PROJECT_ID = "00000000-0000-0000-0000-000000000001"
        const val TEST_INSTALL_ID = "00000000-0000-0000-0000-000000000099"

        /** WireMock server shared by all E2E tests (for mocking external APIs like WeChat) */
        @JvmStatic
        val wireMockServer: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ifmix_test")
            .withUsername("test")
            .withPassword("test")
            .apply { start() }

        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)
            .apply { start() }

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
            registry.add("app.auth.wechat-api-url") { wireMockServer.baseUrl() }
        }
    }

    /** PUT 请求 with projectId header */
    protected fun put(path: String, body: Any? = null) = webClient.put()
        .uri(path)
        .header("x-project-id", TEST_PROJECT_ID)
        .header("x-install-id", TEST_INSTALL_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .apply { if (body != null) bodyValue(body) }

    /** POST 请求 with projectId header */
    protected fun post(path: String, body: Any? = null) = webClient.post()
        .uri(path)
        .header("x-project-id", TEST_PROJECT_ID)
        .header("x-install-id", TEST_INSTALL_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .apply { if (body != null) bodyValue(body) }
}

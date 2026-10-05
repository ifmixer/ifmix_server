package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.entity.ai.AiApiKey
import com.ifmix.core.api.entity.ai.by
import com.ifmix.core.api.entity.ai.enabled
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.provider
import com.ifmix.core.api.modules.ai.service.AiConfig
import org.babyfish.jimmer.kt.new
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

/**
 * disableKeyFn 的真库验证（设计见 docs/design/ai/api-key-disable-and-probe-skip.md）：
 * 插入 enabled=true 行 → 经 [AiConfig.aiApiKeyStore] 组装的 store.disableKey → 断言 enabled=false；
 * 0 行场景（不存在的 key id）为幂等 no-op。
 * 不起 Spring 上下文，PG + Flyway + newKSqlClient 模式沿用 CustomerScanMetricsRepositoryDbTest；
 * StringRedisTemplate mock（本测试只走 disableKey，不触 Redis 接缝）。
 */
class AiConfigDisableKeyDbTest {

    companion object {
        private val pgUrl: String? = System.getenv("TEST_PG_URL")
        private val available = pgUrl != null || DockerClientFactory.instance().isDockerAvailable

        /** 真库 sqlClient（AiConfig 构造与其共享同一实例，保证 disableKey 作用于本测试的库）。 */
        val sqlClient: KSqlClient by lazy {
            val ds = PGSimpleDataSource().apply {
                if (pgUrl != null) setURL(pgUrl) else {
                    val pg = PostgreSQLContainer("postgres:17-alpine").apply { start() }
                    setURL(pg.jdbcUrl); user = pg.username; password = pg.password
                }
            }
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            newKSqlClient {
                setConnectionManager { ds.connection.use { proceed(it) } }
                setDialect(PostgresDialect())
            }
        }
    }

    @BeforeEach
    fun requireDb() = assumeTrue(available, "需 Docker 或 TEST_PG_URL")

    private val config = AiConfig(sqlClient, mock())

    private fun insertKey(): UUID {
        val id = UUID.randomUUID()
        sqlClient.save(
            new(AiApiKey::class).by {
                this.id = id
                this.key = "sk-test-$id"
                this.email = null
                this.type = 10
                this.provider = 10
                this.enabled = true
                this.rateLimit = -1L
                this.windowSec = 86400L
            }
        )
        return id
    }

    @Test
    fun `disableKey flips enabled to false`() {
        val id = insertKey()
        assertThat(enabledOf(id)).isEqualTo(true)

        config.aiApiKeyStore(probeWindow = 5).disableKey(id.toString())

        assertThat(enabledOf(id)).isEqualTo(false)
    }

    @Test
    fun `disableKey on unknown id is an idempotent no-op`() {
        val unknown = UUID.randomUUID()
        // 不存在的 key：affected=0，实现降为 INFO，不抛异常
        config.aiApiKeyStore(probeWindow = 5).disableKey(unknown.toString())
        assertThat(enabledOf(unknown)).isNull()
    }

    @Test
    fun `disableKey is idempotent when already disabled`() {
        val id = insertKey()
        config.aiApiKeyStore(probeWindow = 5).disableKey(id.toString())
        // 重复禁用：第二次 affected=0，仍收敛不外抛
        config.aiApiKeyStore(probeWindow = 5).disableKey(id.toString())
        assertThat(enabledOf(id)).isEqualTo(false)
    }

    private fun enabledOf(id: UUID): Boolean? =
        sqlClient.createQuery(AiApiKey::class) {
            where(table.id eq id)
            where(table.provider eq 10)
            select(table.enabled)
        }.execute().firstOrNull()
}

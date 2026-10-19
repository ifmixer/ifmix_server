package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.entity.ai.AiApiKey
import com.ifmix.core.api.entity.ai.by
import com.ifmix.core.api.entity.ai.enabled
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.provider
import com.ifmix.core.api.entity.ai.updatedAt
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
import java.time.Instant
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

    // lazy：构造阶段不能碰 sqlClient（会触发 Testcontainers 启动），
    // 否则无 Docker/TEST_PG_URL 时实例化即失败，@BeforeEach 的 assumeTrue 跳过形同虚设
    private val config: AiConfig by lazy { AiConfig(sqlClient, mock()) }

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

    /**
     * 禁用必须顺带写 `updated_at`：表上没有 ON UPDATE trigger，Jimmer 局部更新只写 set 过的列。
     * 不写的话 disabled 行的 updated_at 停在插入时刻，排障时无法判断「何时被自动禁用」——
     * 2026-10-10 之前只能靠滚动日志推断（见 docs/ops/issues 的 key 池记录）。
     */
    @Test
    fun `disableKey bumps updated_at`() {
        val id = insertKey()
        val before = updatedAtOf(id)!!
        Thread.sleep(5) // Instant.now() 在同一毫秒内可能取巧相等，跨毫秒才可断言严格递增

        config.aiApiKeyStore(probeWindow = 5).disableKey(id.toString())

        assertThat(enabledOf(id)).isEqualTo(false)
        val after = updatedAtOf(id)!!
        assertThat(after > before).isEqualTo(true)
    }

    /**
     * 重复禁用（affected=0）不应再改 updated_at——否则每次都会把"最后被重试的时间"
     * 当成禁用时间，同样会误导排障。
     */
    @Test
    fun `disableKey no-op does not touch updated_at`() {
        val id = insertKey()
        config.aiApiKeyStore(probeWindow = 5).disableKey(id.toString())
        val afterFirst = updatedAtOf(id)!!
        Thread.sleep(5)

        config.aiApiKeyStore(probeWindow = 5).disableKey(id.toString())

        assertThat(updatedAtOf(id)).isEqualTo(afterFirst)
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

    private fun updatedAtOf(id: UUID): Instant? =
        sqlClient.createQuery(AiApiKey::class) {
            where(table.id eq id)
            where(table.provider eq 10)
            select(table.updatedAt)
        }.execute().firstOrNull()
}

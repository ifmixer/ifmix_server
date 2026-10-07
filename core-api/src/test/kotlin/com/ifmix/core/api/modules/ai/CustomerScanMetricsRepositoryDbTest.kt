package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.ai.repo.CustomerScanMetricsRepository
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

/**
 * core_ai_scanmetrics 真库验证：V9 迁移 + Jimmer INSERT_IF_ABSENT 懒建行 + 带上限原子自增。
 * 不起 Spring 上下文，只用 PG + Flyway + KSqlClient。
 * 库来源：环境变量 TEST_PG_URL（指向一个空库，如 jdbc:postgresql://localhost:5432/tmp?user=postgres），
 * 否则起 Testcontainers（V1 baseline 需 PG17+）；两者都没有则跳过。
 */
class CustomerScanMetricsRepositoryDbTest {

    companion object {
        private const val PROJECT = "test-app"
        private val pgUrl: String? = System.getenv("TEST_PG_URL")
        private val available = pgUrl != null || DockerClientFactory.instance().isDockerAvailable

        private val sql: KSqlClient by lazy {
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

    private val repo = CustomerScanMetricsRepository()
    private fun mc() = ModuleCtx(action = ActionContext(projectId = PROJECT), sql = sql)

    @Test
    fun `lazy row, capped increment, merge add`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertThat(repo.findCounts(mc(), PROJECT, a)).isNull()

        // limit=2：前两次计入，第三次拒绝；每次都会重复懒建（ON CONFLICT DO NOTHING），不能报错
        assertThat(repo.tryIncrementScanCount(mc(), PROJECT, a, 2)).isEqualTo(1)
        assertThat(repo.tryIncrementScanCount(mc(), PROJECT, a, 2)).isEqualTo(1)
        assertThat(repo.tryIncrementScanCount(mc(), PROJECT, a, 2)).isEqualTo(0)
        assertThat(repo.tryIncrementDeepResearchCount(mc(), PROJECT, a, 1)).isEqualTo(1)
        assertThat(repo.findCounts(mc(), PROJECT, a)).isEqualTo(2 to 1)

        // 合并：target 无行时懒建再加总
        assertThat(repo.addCounts(mc(), PROJECT, b, 2, 1)).isEqualTo(1)
        assertThat(repo.findCounts(mc(), PROJECT, b)).isEqualTo(2 to 1)
    }
    @Test
    fun `pending reservation prevents oversubscription and settles exactly once`() {
        val customer = UUID.randomUUID()

        assertThat(repo.reserveScan(mc(), PROJECT, customer, 1)).isEqualTo(1)
        assertThat(repo.reserveScan(mc(), PROJECT, customer, 1)).isEqualTo(0)
        assertThat(repo.releaseScan(mc(), PROJECT, customer)).isEqualTo(1)
        assertThat(repo.releaseScan(mc(), PROJECT, customer)).isEqualTo(0)
        assertThat(repo.reserveScan(mc(), PROJECT, customer, 1)).isEqualTo(1)
        assertThat(repo.completeScan(mc(), PROJECT, customer)).isEqualTo(1)
        assertThat(repo.completeScan(mc(), PROJECT, customer)).isEqualTo(0)
        assertThat(repo.findCounts(mc(), PROJECT, customer)).isEqualTo(1 to 0)
    }
}

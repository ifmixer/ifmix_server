package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.ifmix.core.api.entity.ai.AiApiKey
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.Executor
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 探针（临时）：验证 Jimmer 0.11.5 的 `entities.forUpdate()` 在 PG 方言下是否真的渲染 `for update`，
 * 以及是否复用传入的连接（即能否接进当前事务）。
 */
class ScratchRowLockProbeTest {

    private class SqlRecorder(private val inner: Executor) : Executor by inner {
        val sqls = Collections.synchronizedList(mutableListOf<String>())
        override fun <R> execute(args: Executor.Args<R>): R {
            sqls.add(args.sql)
            return inner.execute(args)
        }
    }

    companion object {
        private val pgUrl: String? = System.getenv("TEST_PG_URL")
        private val recorder = SqlRecorder(Executor.log())
        private val ds: PGSimpleDataSource by lazy {
            PGSimpleDataSource().apply { setURL(pgUrl!!) }.also {
                Flyway.configure().dataSource(it).locations("classpath:db/migration").load().migrate()
            }
        }
        val sqlClient: KSqlClient by lazy {
            newKSqlClient {
                setConnectionManager { ds.connection.use { proceed(it) } }
                setDialect(PostgresDialect())
                setExecutor(recorder)
            }
        }
    }

    @BeforeEach
    fun requireDb() = assumeTrue(pgUrl != null, "需 TEST_PG_URL")

    @Test
    fun `entities forUpdate emits FOR UPDATE and blocks concurrent locker`() {
        val id = UUID.randomUUID()
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO core_ai_apikey (id, key, type, enabled, provider) VALUES (?,?,10,true,10)"
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "probe-$id")
                ps.executeUpdate()
            }
        }
        recorder.sqls.clear()

        val conA = ds.connection
        conA.autoCommit = false
        val rowA = sqlClient.entities.forConnection(conA).forUpdate().findById(AiApiKey::class, id)
        assertThat(rowA).isNotNull()
        val hasForUpdate = recorder.sqls.any { it.contains("for update", ignoreCase = true) }
        println("CAPTURED_SQL=" + recorder.sqls.filter { it.contains("apikey") })

        val conB = ds.connection
        conB.autoCommit = false
        conB.prepareStatement("SET lock_timeout = '400ms'").execute()
        val done = CountDownLatch(1)
        val outcome = AtomicReference<String?>(null)
        val t = thread {
            try {
                sqlClient.entities.forConnection(conB).forUpdate().findById(AiApiKey::class, id)
                outcome.set("NO_BLOCK")
            } catch (e: Exception) {
                outcome.set(e.message)
            } finally {
                done.countDown()
            }
        }
        done.await(6, TimeUnit.SECONDS)
        val blocked = outcome.get()?.contains("lock timeout", ignoreCase = true) ?: false
        println("OUTCOME=" + outcome.get())

        conA.commit()
        conA.close()
        conB.close()
        t.join(3000)
        ds.connection.use { c ->
            c.prepareStatement("DELETE FROM core_ai_apikey WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }

        assertThat(hasForUpdate, "SQL 必须含 for update").isTrue()
        assertThat(blocked, "并发第二次加锁必须被阻塞（行锁生效）").isTrue()
    }
}

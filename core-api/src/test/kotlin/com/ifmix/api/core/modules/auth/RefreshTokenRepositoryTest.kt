package com.ifmix.core.api.modules.auth.repo

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class RefreshTokenRepositoryTest {

    @Test
    fun `expired timestamp does not invalidate an unrevoked refresh token`() {
        val fixture = fixture(revokedAt = null)

        assertThat(
            fixture.repo.findValidByHash(fixture.mc, PROJECT_ID, fixture.tokenHash),
        ).isNotNull()
    }

    @Test
    fun `expired timestamp still counts as a valid token for cleanup`() {
        val fixture = fixture(revokedAt = null)

        assertThat(
            fixture.repo.hasValidToken(fixture.mc, PROJECT_ID, fixture.actorId, ACTOR_TYPE),
        ).isTrue()
    }

    @Test
    fun `revoked refresh token remains invalid`() {
        val fixture = fixture(revokedAt = Instant.now())

        assertThat(
            fixture.repo.findValidByHash(fixture.mc, PROJECT_ID, fixture.tokenHash),
        ).isNull()
        assertThat(
            fixture.repo.hasValidToken(fixture.mc, PROJECT_ID, fixture.actorId, ACTOR_TYPE),
        ).isFalse()
    }

    private fun fixture(revokedAt: Instant?): Fixture {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE core_auth_refreshtoken (
                        id UUID PRIMARY KEY,
                        project_id VARCHAR(30) NOT NULL,
                        actor_id UUID NOT NULL,
                        actor_type SMALLINT NOT NULL,
                        token_hash VARCHAR(128) NOT NULL,
                        expires_at TIMESTAMP WITH TIME ZONE,
                        revoked_at TIMESTAMP WITH TIME ZONE,
                        replaced_by UUID,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
        val actorId = UUID.randomUUID()
        val tokenHash = "token-${UUID.randomUUID()}"
        val now = Instant.now()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO core_auth_refreshtoken(
                    id, project_id, actor_id, actor_type, token_hash,
                    expires_at, revoked_at, replaced_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, PROJECT_ID)
                statement.setObject(3, actorId)
                statement.setInt(4, ACTOR_TYPE)
                statement.setString(5, tokenHash)
                statement.setTimestamp(6, Timestamp.from(now.minus(1, ChronoUnit.DAYS)))
                statement.setTimestamp(7, revokedAt?.let(Timestamp::from))
                statement.setTimestamp(8, Timestamp.from(now.minus(2, ChronoUnit.DAYS)))
                statement.setTimestamp(9, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
        val sql = newKSqlClient {
            setConnectionManager {
                dataSource.connection.use { connection -> proceed(connection) }
            }
        }
        return Fixture(
            repo = RefreshTokenRepository(),
            mc = ModuleCtx(ActionContext(projectId = PROJECT_ID), sql),
            actorId = actorId,
            tokenHash = tokenHash,
        )
    }

    private data class Fixture(
        val repo: RefreshTokenRepository,
        val mc: ModuleCtx,
        val actorId: UUID,
        val tokenHash: String,
    )

    companion object {
        private const val PROJECT_ID = "test-app"
        private const val ACTOR_TYPE = 10
    }
}

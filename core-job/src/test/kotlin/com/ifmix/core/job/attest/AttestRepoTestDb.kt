package com.ifmix.core.job.attest

import org.springframework.jdbc.core.simple.JdbcClient
import javax.sql.DataSource
import java.time.Instant
import java.util.UUID

/**
 * AttestationRepo 的 H2 内存 PG 模式测试基建：建表 + 造行，[AttestationRepo] 经 JdbcClient 走真 SQL。
 *
 * H2 的 PostgreSQL 模式不认 `TIMESTAMPTZ` 简写，DDL 用 `TIMESTAMP WITH TIME ZONE`（语义等价）；
 * JSONB 在 H2 PG 模式下可直接建列、按字符串存取（与 PG JDBC 驱动 `getString` 读 JSONB 的行为一致）。
 */
object AttestRepoTestDb {
    const val URL = "jdbc:h2:mem:attest_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"

    fun datasource(): DataSource {
        val ds = org.springframework.jdbc.datasource.DriverManagerDataSource(
            URL, "sa", "",
        )
        ds.setDriverClassName("org.h2.Driver")
        return ds
    }

    /**
     * H2 mem（DB_CLOSE_DELAY=-1）在同一 JVM 内跨测试类共享：
     * @BeforeAll 先 DROP 再建表（类级一次），@BeforeEach 用 [truncate] 清数据（用例级）。
     */
    fun setupTable(jdbc: JdbcClient) {
        jdbc.sql("DROP TABLE IF EXISTS core_auth_installattestation").update()
        jdbc.sql("DROP TABLE IF EXISTS core_project_serverconfig").update()
        jdbc.sql(
            """
            CREATE TABLE core_auth_installattestation (
                id UUID PRIMARY KEY,
                project_id TEXT NOT NULL,
                install_id UUID NOT NULL,
                provider INT NOT NULL,
                subject TEXT NULL,
                public_key BYTEA NULL,
                attestation_object BYTEA NULL,
                sign_count BIGINT NOT NULL DEFAULT 0,
                receipt BYTEA NULL,
                receipt_expires_at TIMESTAMP WITH TIME ZONE NULL,
                next_refresh_at TIMESTAMP WITH TIME ZONE NULL,
                refresh_failure_count INT NOT NULL DEFAULT 0,
                fraud_metric INT NULL,
                signals JSONB NOT NULL,
                evidence JSONB NULL,
                status INT NOT NULL,
                verify_status INT NOT NULL DEFAULT 10,
                challenge TEXT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                last_used_at TIMESTAMP WITH TIME ZONE NULL
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE core_project_serverconfig (
                id UUID PRIMARY KEY,
                project_id VARCHAR(64) NOT NULL,
                fcm_config JSONB,
                app_attest_config JSONB,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
            )
            """.trimIndent(),
        ).update()
    }

    /** H2 mem（DB_CLOSE_DELAY=-1）跨测试类共享：每个用例 @BeforeEach 清空两表，用例间互不污染。 */
    fun truncate(jdbc: JdbcClient) {
        jdbc.sql("TRUNCATE TABLE core_auth_installattestation").update()
        jdbc.sql("TRUNCATE TABLE core_project_serverconfig").update()
    }

    /** 造一行回填候选（provider=110 / status=10 / receipt NULL / attestation_object 非空）。 */
    fun insertBackfillCandidate(
        jdbc: JdbcClient,
        projectId: String = "p1",
        keyId: String = "key-a",
        attestationObject: ByteArray = "raw-attest".toByteArray(),
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO core_auth_installattestation (
                id, project_id, install_id, provider, subject, attestation_object,
                signals, status, created_at, updated_at
            ) VALUES (:id, :projectId, :installId, 110, :subject, :attObject,
                      '{"verdict": "seed"}', 10, now(), now())
            """.trimIndent(),
        )
            .param("id", id)
            .param("projectId", projectId)
            .param("installId", UUID.randomUUID())
            .param("subject", keyId)
            .param("attObject", attestationObject)
            .update()
        return id
    }

    /** 造一行刷新候选（receipt 非空 + next_refresh_at 到期/未到期）。 */
    fun insertRefreshCandidate(
        jdbc: JdbcClient,
        projectId: String = "p1",
        receipt: ByteArray = "receipt".toByteArray(),
        nextRefreshAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO core_auth_installattestation (
                id, project_id, install_id, provider, subject, receipt, next_refresh_at,
                signals, status, created_at, updated_at
            ) VALUES (:id, :projectId, :installId, 110, :subject, :receipt, :nextRefreshAt,
                      '{"verdict": "seed"}', 10, now(), now())
            """.trimIndent(),
        )
            .param("id", id)
            .param("projectId", projectId)
            .param("installId", UUID.randomUUID())
            .param("subject", "key-r")
            .param("receipt", receipt)
            .param("nextRefreshAt", java.sql.Timestamp.from(nextRefreshAt))
            .update()
        return id
    }

    /** 造一行 evidence 待清理行（evidence 用 JSON 原文字面量存，规避 H2 绑参 JSONB 的双重转义差异）。 */
    fun insertEvidenceRow(jdbc: JdbcClient, createdAt: Instant, evidence: String = "{\"verdict\":\"ok\"}"): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO core_auth_installattestation (
                id, project_id, install_id, provider, signals, evidence, status, created_at, updated_at
            ) VALUES (:id, :projectId, :installId, 110,
                      '{"verdict": "seed"}', :evidence::jsonb, 10, :createdAt, now())
            """.trimIndent(),
        )
            .param("id", id)
            .param("projectId", "p1")
            .param("installId", UUID.randomUUID())
            .param("evidence", evidence)
            .param("createdAt", java.sql.Timestamp.from(createdAt))
            .update()
        return id
    }

    /** 造一行 evidence 为 NULL 的行（验证清理 SQL 不碰 evidence 本就为 NULL 的行）。 */
    fun insertNoEvidenceRow(jdbc: JdbcClient, createdAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO core_auth_installattestation (
                id, project_id, install_id, provider, signals, evidence, status, created_at, updated_at
            ) VALUES (:id, :projectId, :installId, 110,
                      '{"verdict": "seed"}', NULL, 10, :createdAt, now())
            """.trimIndent(),
        )
            .param("id", id)
            .param("projectId", "p1")
            .param("installId", UUID.randomUUID())
            .param("createdAt", java.sql.Timestamp.from(createdAt))
            .update()
        return id
    }

    /** 造一行 project 配置（app_attest_config JSONB；null = 未配置）。 */
    fun insertProjectConfig(jdbc: JdbcClient, projectId: String, appAttestConfigJson: String?) {
        // H2 PG 模式：绑参写 JSONB 列要 `?::jsonb` 强转，否则 H2 把参数值当普通字符串再转义一层
        //（JSON 原文里的 `"` 变成 `\"`），与 PG JDBC 驱动读 JSONB 返回裸 JSON 的行为不一致。
        jdbc.sql(
            """
            INSERT INTO core_project_serverconfig (id, project_id, app_attest_config)
            VALUES (:id, :projectId, :cfg::jsonb)
            """.trimIndent(),
        )
            .param("id", UUID.randomUUID())
            .param("projectId", projectId)
            .param("cfg", appAttestConfigJson)
            .update()
    }

    fun snapshot(jdbc: JdbcClient, id: UUID): Map<String, Any?> =
        jdbc.sql(
            """
            SELECT receipt, attestation_object, fraud_metric, next_refresh_at,
                   refresh_failure_count, evidence
            FROM core_auth_installattestation WHERE id = :id
            """.trimIndent(),
        ).param("id", id).query { rs, _ ->
            val fmInt = rs.getInt("fraud_metric")
            val fmNull = rs.wasNull()
            mapOf(
                "receipt" to rs.getBytes("receipt"),
                "attestation_object" to rs.getBytes("attestation_object"),
                "fraud_metric" to if (fmNull) null else fmInt,
                "next_refresh_at" to rs.getTimestamp("next_refresh_at")?.toInstant(),
                "refresh_failure_count" to rs.getInt("refresh_failure_count"),
                "evidence" to rs.getString("evidence"),
            )
        }.single()
}

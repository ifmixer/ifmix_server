package com.ifmix.core.job.attest

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * install attestation 任务共用的数据访问（JdbcClient；core-job 约定不用 JdbcTemplate / Jimmer）。
 *
 * 三个 WP-E 任务的读 / 写：
 *  - 回填候选：`provider=110 AND status=10 AND receipt IS NULL AND attestation_object IS NOT NULL`
 *    且 `next_refresh_at IS NULL OR <= now`（退避行到期才再取，§5.8）；
 *  - 刷新候选：`provider=110 AND status=10 AND receipt IS NOT NULL AND next_refresh_at <= now`；
 *  - evidence 清理：`evidence IS NOT NULL AND created_at < cutoff`（§5.7）。
 *
 * 所有写都是带 `status = 10` 守卫的 UPDATE（行可能已被并发 RETIRED/BLOCKED，影响 0 行时调用方记日志跳过）。
 */
@Component
class AttestationRepo(
    @Qualifier("businessDataSource") dataSource: DataSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = JdbcClient.create(dataSource)

    /** 回填候选行（§5.8：attestation_object 原文待换 receipt）。 */
    data class BackfillRow(
        val id: UUID,
        val projectId: String,
        val keyId: String,
        val attestationObject: ByteArray,
        val failureCount: Int,
    )

    /** 刷新候选行（receipt 已就绪且 next_refresh_at 到期）。 */
    data class RefreshRow(
        val id: UUID,
        val projectId: String,
        val receipt: ByteArray,
        val failureCount: Int,
    )

    /**
     * 回填候选行（§5.8：attestation_object 原文待换 receipt）。
     *
     * 额外守卫 `next_refresh_at IS NULL OR <= now`：退避后的行（[markBackoff] 把 next_refresh_at
     * 推到未来）本轮不再命中，直到退避到期 —— 保证 cleaner 的分批循环对退避行终止。
     */
    fun fetchBackfillRows(now: Instant, limit: Int): List<BackfillRow> =
        jdbc.sql(
            """
            SELECT id, project_id, subject, attestation_object, refresh_failure_count
            FROM core_auth_install_attestation
            WHERE provider = 110 AND status = 10
              AND receipt IS NULL AND attestation_object IS NOT NULL
              AND (next_refresh_at IS NULL OR next_refresh_at <= :now)
            ORDER BY created_at
            LIMIT :limit
            """.trimIndent(),
        ).param("now", java.sql.Timestamp.from(now)).param("limit", limit)
            .query { rs, _ ->
                BackfillRow(
                    id = rs.getObject("id", UUID::class.java),
                    projectId = rs.getString("project_id"),
                    keyId = rs.getString("subject") ?: "",
                    attestationObject = rs.getBytes("attestation_object"),
                    failureCount = rs.getInt("refresh_failure_count"),
                )
            }.list()

    fun fetchRefreshRows(now: Instant, limit: Int): List<RefreshRow> =
        jdbc.sql(
            """
            SELECT id, project_id, receipt, refresh_failure_count
            FROM core_auth_install_attestation
            WHERE provider = 110 AND status = 10
              AND receipt IS NOT NULL AND next_refresh_at <= :now
            ORDER BY next_refresh_at
            LIMIT :limit
            """.trimIndent(),
        ).param("now", java.sql.Timestamp.from(now)).param("limit", limit)
            .query { rs, _ ->
                RefreshRow(
                    id = rs.getObject("id", UUID::class.java),
                    projectId = rs.getString("project_id"),
                    receipt = rs.getBytes("receipt"),
                    failureCount = rs.getInt("refresh_failure_count"),
                )
            }.list()

    /** 回填成功：写 receipt + next_refresh_at（now+refreshIntervalHours 由调用方传入）+ 清 attestation_object。 */
    fun markBackfilled(id: UUID, receipt: ByteArray, nextRefreshAt: Instant): Int =
        jdbc.sql(
            """
            UPDATE core_auth_install_attestation
            SET receipt = :receipt,
                next_refresh_at = :nextRefreshAt,
                refresh_failure_count = 0,
                attestation_object = NULL,
                updated_at = :updatedAt
            WHERE id = :id AND status = 10
            """.trimIndent(),
        ).param("id", id)
            .param("receipt", receipt)
            .param("nextRefreshAt", java.sql.Timestamp.from(nextRefreshAt))
            .param("updatedAt", java.sql.Timestamp.from(Instant.now()))
            .update()

    /** 「已使用」类 4xx：清 attestation_object，放弃该 key 的 receipt（规格 §5.8，不再重试）。 */
    fun clearAttestationObject(id: UUID): Int =
        jdbc.sql(
            """
            UPDATE core_auth_install_attestation
            SET attestation_object = NULL, updated_at = :updatedAt
            WHERE id = :id AND status = 10
            """.trimIndent(),
        ).param("id", id).param("updatedAt", java.sql.Timestamp.from(Instant.now())).update()

    /** 网络 / 5xx / 429：refresh_failure_count + 1、next_refresh_at = backoffUntil（保留 attestation_object 等下轮）。 */
    fun markBackoff(id: UUID, backoffUntil: Instant): Int =
        jdbc.sql(
            """
            UPDATE core_auth_install_attestation
            SET refresh_failure_count = refresh_failure_count + 1,
                next_refresh_at = :backoffUntil,
                updated_at = :updatedAt
            WHERE id = :id AND status = 10
            """.trimIndent(),
        ).param("id", id)
            .param("backoffUntil", java.sql.Timestamp.from(backoffUntil))
            .param("updatedAt", java.sql.Timestamp.from(Instant.now()))
            .update()

    /** fraud metric 刷新成功（§2 决策 2：响应不含新 receipt，receipt_expires_at 一期不写）。 */
    fun markFraudMetric(id: UUID, fraudMetric: Int, nextRefreshAt: Instant): Int =
        jdbc.sql(
            """
            UPDATE core_auth_install_attestation
            SET fraud_metric = :metric,
                next_refresh_at = :nextRefreshAt,
                refresh_failure_count = 0,
                updated_at = :updatedAt
            WHERE id = :id AND status = 10
            """.trimIndent(),
        ).param("id", id)
            .param("metric", fraudMetric)
            .param("nextRefreshAt", java.sql.Timestamp.from(nextRefreshAt))
            .param("updatedAt", java.sql.Timestamp.from(Instant.now()))
            .update()

    /** fraud metric 刷新失败退避。 */
    fun markRefreshBackoff(id: UUID, backoffUntil: Instant): Int =
        jdbc.sql(
            """
            UPDATE core_auth_install_attestation
            SET refresh_failure_count = refresh_failure_count + 1,
                next_refresh_at = :backoffUntil,
                updated_at = :updatedAt
            WHERE id = :id AND status = 10
            """.trimIndent(),
        ).param("id", id)
            .param("backoffUntil", java.sql.Timestamp.from(backoffUntil))
            .param("updatedAt", java.sql.Timestamp.from(Instant.now()))
            .update()

    /** evidence 90 天清理（§5.7）：cutoff = now - retainDays 由调用方计算（可测）。 */
    fun clearOldEvidence(cutoff: Instant): Int =
        jdbc.sql(
            """
            UPDATE core_auth_install_attestation
            SET evidence = NULL, updated_at = :updatedAt
            WHERE evidence IS NOT NULL AND created_at < :cutoff
            """.trimIndent(),
        ).param("updatedAt", java.sql.Timestamp.from(Instant.now()))
            .param("cutoff", java.sql.Timestamp.from(cutoff))
            .update()

    /**
     * per-project attest 配置（`core_project_server_config.app_attest_config` JSONB → 字符串解析 ios 段；
     * 行不存在 / NULL / 解析失败 → null）。
     *
     * H2 测试环境（PG 模式）经 JDBC 写 JSONB 列会存成 JSON 字符串字面量（多包一层引号），
     * 而 PG 驱动读 JSONB 返回裸 JSON —— 兼容解包：整体是带引号的 JSON 字符串时先去壳再解析。
     */
    fun loadIosConfig(projectId: String): AppAttestIosConfig? =
        jdbc.sql(
            "SELECT app_attest_config FROM core_project_server_config WHERE project_id = :projectId",
        ).param("projectId", projectId)
            .query { rs, _ ->
                val raw = normalizeJsonBValue(rs.getString("app_attest_config"))
                val parsed = AppAttestConfig.parse(raw)
                if (parsed == null && raw != null) {
                    log.warn("[attest] app_attest_config JSON 解析失败，按未配置处理. projectId={}", projectId)
                }
                parsed?.ios
            }.optional().orElse(null)

    /**
     * 整体是 `"{...}"`（JSON 字符串字面量包 JSON 原文，H2 PG 模式 JDBC 写 JSONB 的存法）→ 去壳；
     * 其它（PG 驱动读 JSONB 的裸 JSON / null）原样。
     */
    companion object {
        fun normalizeJsonBValue(raw: String?): String? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.length < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return raw
            return try {
                tools.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(trimmed, String::class.java)
            } catch (e: Exception) {
                raw
            }
        }
    }
}

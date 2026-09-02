package com.ifmix.core.job.customer

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * 匿名 Customer 清理任务（core-job 独立进程版）。
 *
 * 不用 Jimmer/entity：用 [JdbcClient] + 裸 SQL 游标扫候选 + 逐批物理删。
 * 两类候选，各自按 id 游标分批 LIMIT 循环，幂等可重入：
 *  - 未合并僵尸：anonymous=true AND merged_to IS NULL AND 无有效 refresh token。
 *  - 已合并 tombstone：merged_to IS NOT NULL AND updated_at < cutoff（超审计窗口）。
 * 逐候选二次校验 hasActiveSubscription（命中跳过 + WARN）/ hasValidToken，交
 * [AnonymousCleanupDecision.shouldDelete] 裁决。删 customer 前同事务先删其资源避免孤儿行。
 *
 * 绝不碰 anonymous=false 且 merged_to IS NULL 的正常用户：候选 SQL 不命中，
 * 且 [AnonymousCleanupDecision] 对该组合恒 false（双保险）。
 *
 * 注入 [DataSource] 构建 JdbcClient/TransactionTemplate —— 触发数据源装配。
 */
@Component
@EnableConfigurationProperties(AnonymousCleanupConfig::class)
class AnonymousCleanupCleaner(
    private val config: AnonymousCleanupConfig,
    @Qualifier("businessDataSource") dataSource: DataSource,
    @Qualifier("businessTransactionManager") txManager: org.springframework.transaction.PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = JdbcClient.create(dataSource)
    private val tx = TransactionTemplate(txManager)

    companion object {
        private const val ACTOR_TYPE = 10
        /** 单类候选运行的批次上限，防止异常数据导致死循环。 */
        private const val MAX_BATCHES = 10_000
        /** 先删的资源表（均以 customer_id 关联），最后才删 customer。 */
        private val RESOURCE_TABLES = listOf(
            "ai_scan_collection",
            "ai_scan_record",
            "media_upload_record",
            "demo_todo",
            "cms_feedback",
            "pay_subscription",
        )
    }

    private data class Candidate(val id: UUID, val appId: UUID, val anonymous: Boolean, val merged: Boolean)

    /** 执行一次全量清理（由 Spring Batch tasklet 调用，返回 RepeatStatus.FINISHED）。 */
    fun runOnce() {
        val started = Instant.now()
        val cutoff = Instant.now().minus(config.tombstoneWindowDays, ChronoUnit.DAYS)

        // 单库全库扫（ifmix_core_test 单 app）。SQL 仍带 app_id 字段回读，删除按 id 精确。
        val zombieDeleted = drain { afterId -> fetchZombies(afterId) }
        val tombstoneDeleted = drain { afterId -> fetchTombstones(cutoff, afterId) }

        log.info(
            "[anon-cleanup] 完成：zombieDeleted={} tombstoneDeleted={} elapsedMs={}",
            zombieDeleted, tombstoneDeleted, ChronoUnit.MILLIS.between(started, Instant.now()),
        )
    }

    /**
     * 分批循环：每批在独立事务内取候选（id > 上批末尾，升序）→ 逐个二次校验 → 物理删。
     * 用 id 游标推进（而非 offset），因为删除改变行集；被跳过的候选（有效 token / active 订阅）
     * 也算已检视，游标越过它们，不因整批被跳过而提前终止。空批终止。
     */
    private fun drain(fetch: (UUID?) -> List<Candidate>): Int {
        var deleted = 0
        var afterId: UUID? = null
        var batches = 0
        while (batches++ < MAX_BATCHES) {
            val batch = fetch(afterId)
            if (batch.isEmpty()) break
            afterId = batch.last().id
            deleted += tx.execute { deleteBatch(batch) } ?: 0
        }
        return deleted
    }

    /** 未合并匿名僵尸候选。 */
    private fun fetchZombies(afterId: UUID?): List<Candidate> =
        jdbc.sql(
            """
            SELECT id, app_id, anonymous, (merged_to IS NOT NULL) AS merged
            FROM customer
            WHERE anonymous = true AND merged_to IS NULL AND (:afterId::uuid IS NULL OR id > :afterId::uuid)
            ORDER BY id
            LIMIT :batch
            """.trimIndent(),
        ).param("afterId", afterId).param("batch", config.batchSize)
            .query { rs, _ ->
                Candidate(
                    id = rs.getObject("id", UUID::class.java),
                    appId = rs.getObject("app_id", UUID::class.java),
                    anonymous = rs.getBoolean("anonymous"),
                    merged = rs.getBoolean("merged"),
                )
            }.list()

    /** 已合并 tombstone 候选（updated_at < cutoff，已过滤 → tombstoneExpired 恒 true）。 */
    private fun fetchTombstones(cutoff: Instant, afterId: UUID?): List<Candidate> =
        jdbc.sql(
            """
            SELECT id, app_id, anonymous, (merged_to IS NOT NULL) AS merged
            FROM customer
            WHERE merged_to IS NOT NULL AND updated_at < :cutoff
              AND (:afterId::uuid IS NULL OR id > :afterId::uuid)
            ORDER BY id
            LIMIT :batch
            """.trimIndent(),
        ).param("cutoff", java.sql.Timestamp.from(cutoff))
            .param("afterId", afterId).param("batch", config.batchSize)
            .query { rs, _ ->
                Candidate(
                    id = rs.getObject("id", UUID::class.java),
                    appId = rs.getObject("app_id", UUID::class.java),
                    anonymous = rs.getBoolean("anonymous"),
                    merged = rs.getBoolean("merged"),
                )
            }.list()

    /** 逐候选二次校验 + 批量物理删（同事务先资源后主体）。返回本批删除的 customer 数。 */
    private fun deleteBatch(batch: List<Candidate>): Int {
        val toDelete = mutableListOf<UUID>()
        for (c in batch) {
            if (hasActiveSubscription(c.id)) {
                log.warn("[anon-cleanup] customer={} 命中 active 订阅，跳过删除（匿名却付费边界数据）", c.id)
                continue
            }
            val state = AnonymousCleanupDecision.CustomerState(
                anonymous = c.anonymous,
                merged = c.merged,
                tombstoneExpired = true, // 候选 SQL 已按 cutoff 过滤，tombstone 必超窗
                hasValidToken = hasValidToken(c.id),
                hasActiveSubscription = false,
            )
            if (AnonymousCleanupDecision.shouldDelete(state)) toDelete.add(c.id)
        }
        if (toDelete.isEmpty()) return 0

        // 先删资源避免孤儿行，最后删 customer。
        jdbc.sql(
            "DELETE FROM ai_scan_collection_item WHERE collection_id IN " +
                "(SELECT id FROM ai_scan_collection WHERE customer_id IN (:ids))",
        ).param("ids", toDelete).update()
//        for (table in RESOURCE_TABLES) {
//            jdbc.sql("DELETE FROM $table WHERE customer_id IN (:ids)").param("ids", toDelete).update()
//        }
        val n = jdbc.sql("DELETE FROM customer WHERE id IN (:ids)").param("ids", toDelete).update()
        log.info("[anon-cleanup] 删除 customer {} 个", n)
        return n
    }

    private fun hasActiveSubscription(id: UUID): Boolean =
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM pay_subscription WHERE customer_id = :id AND active = true)")
            .param("id", id).query(Boolean::class.java).single()

    private fun hasValidToken(id: UUID): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS(
              SELECT 1 FROM auth_refreshtoken
              WHERE actor_id = :id AND actor_type = :actorType
                AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())
            )
            """.trimIndent(),
        ).param("id", id).param("actorType", ACTOR_TYPE).query(Boolean::class.java).single()
}

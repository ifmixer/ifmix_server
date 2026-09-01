package com.ifmix.api.core.modules.customer.handler

import com.ifmix.api.core.infra.db.ClusterRouter
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.tx.GlobalTxRunner
import com.ifmix.api.core.modules.ai.repo.ScanCollectionItemRepository
import com.ifmix.api.core.modules.ai.repo.ScanCollectionRepository
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import com.ifmix.api.core.modules.app.repo.AppInfoRepository
import com.ifmix.api.core.modules.auth.repo.RefreshTokenRepository
import com.ifmix.api.core.modules.cms.repo.FeedbackRepository
import com.ifmix.api.core.modules.customer.AnonymousCleanupConfig
import com.ifmix.api.core.modules.customer.repo.CustomerRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import com.ifmix.api.core.modules.media.repo.UploadRecordRepository
import com.ifmix.api.core.modules.pay.repo.SubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 阶段 6：匿名 Customer 清理定时任务。
 *
 * 每天一次（cron 可配）。两类都清，分批 LIMIT 循环，幂等可重入，物理删含其资源：
 *  - 未合并僵尸：anonymous=true AND merged_to IS NULL AND 无有效 refresh token。
 *  - 已合并 tombstone：merged_to IS NOT NULL AND 合并时间超审计窗口。
 * 删前扫 active subscription：命中则跳过 + WARN 告警。
 * 删 customer 前先同事务删其资源（scan_record/scan_collection(+item)/upload_record/todo/feedback/subscription）避免孤儿行。
 *
 * 主体类型固定 "customer"（refresh token / actor 语义）。方向硬编码，绝不碰
 * anonymous=false 且 mergedTo=null 的正常用户（判定见 [AnonymousCleanupDecision]）。
 */
@Component
class AnonymousCleanupScheduler(
    private val config: AnonymousCleanupConfig,
    private val txRunner: GlobalTxRunner,
    private val ctxFactory: ModuleCtxFactory,
    private val router: ClusterRouter,
    private val appInfoRepo: AppInfoRepository,
    private val customerRepo: CustomerRepository,
    private val refreshTokenRepo: RefreshTokenRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val scanRecordRepo: ScanRecordRepository,
    private val scanCollectionRepo: ScanCollectionRepository,
    private val scanCollectionItemRepo: ScanCollectionItemRepository,
    private val uploadRecordRepo: UploadRecordRepository,
    private val todoRepo: TodoRepository,
    private val feedbackRepo: FeedbackRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val ACTOR_TYPE = "customer"
        /** 单次任务运行的批次上限，防止异常数据导致死循环。 */
        private const val MAX_BATCHES_PER_APP = 10_000
        /** bootstrap 路由占位（单集群下 router 忽略 appId 参数）。 */
        private val UuidZero: UUID = UUID(0L, 0L)
        /** 空批哨兵（GlobalTxRunner.withTx 用 !! 解包，body 不能返回 null）。 */
        private val EMPTY_BATCH = BatchResult(lastId = null, deleted = 0)
    }

    private data class BatchResult(val lastId: UUID?, val deleted: Int)

    @Scheduled(cron = "\${app.customer.cleanup.cron:0 30 3 * * *}")
    fun run() {
        val started = Instant.now()
        // 无请求上下文：合成一个 OperationContext（mutation 语义 → 走 writer）。
        // 枚举 appId 是全局读（app_info 不分片），直接用 writer KSqlClient 构建 bootstrap ctx，
        // 不经 ModuleCtxFactory.forApp（那会强制要求 appId）。ponytail: 单集群，路由参数被忽略。
        val bootstrapOp = newOpContext(appId = null)
        val bootstrapCtx = ModuleCtx(
            op = bootstrapOp,
            sql = router.forApp(UuidZero).writer,
        )
        val appIds = runCatching { appInfoRepo.findAllIds(bootstrapCtx) }
            .onFailure { log.error("[anon-cleanup] 枚举 appId 失败，跳过本轮", it) }
            .getOrDefault(emptyList())

        var totalDeleted = 0
        for (appId in appIds) {
            totalDeleted += runCatching { cleanupApp(appId) }
                .onFailure { log.error("[anon-cleanup] app={} 清理失败（跳过该 app）", appId, it) }
                .getOrDefault(0)
        }
        log.info(
            "[anon-cleanup] 完成：apps={} deletedCustomers={} elapsedMs={}",
            appIds.size, totalDeleted, ChronoUnit.MILLIS.between(started, Instant.now()),
        )
    }

    /** 处理单个 app：两类候选各自分批循环，直到无候选或达上限。返回删除的 customer 数。 */
    private fun cleanupApp(appId: UUID): Int {
        val tombstoneCutoff = Instant.now().minus(config.tombstoneWindowDays, ChronoUnit.DAYS)
        var deleted = 0

        // 1) 未合并匿名僵尸
        deleted += drainBatches(appId) { mc, afterId ->
            customerRepo.findAnonymousZombieCandidates(mc, appId, afterId, config.batchSize)
        }
        // 2) 已合并 tombstone
        deleted += drainBatches(appId) { mc, afterId ->
            customerRepo.findMergedTombstoneCandidates(mc, appId, tombstoneCutoff, afterId, config.batchSize)
        }
        return deleted
    }

    /**
     * 分批循环：每批在独立事务内取候选（id > 上批末尾，升序）→ 逐个二次校验 → 物理删。
     * 用 id 游标推进（而非固定 offset），因为删除会改变行集；被跳过的候选（持有效 token /
     * active 订阅）也算已检视，游标越过它们，不会因整批被跳过而提前终止。空批才终止。
     * 幂等可重入：单批事务提交，任务中断后重跑从头扫描，已删的行不再是候选。
     */
    private fun drainBatches(appId: UUID, fetch: (ModuleCtx, UUID?) -> List<UUID>): Int {
        var deleted = 0
        var afterId: UUID? = null
        var batches = 0
        while (batches++ < MAX_BATCHES_PER_APP) {
            val batch = txRunner.withTx(newOpContext(appId)) { txOp ->
                val mc = ctxFactory.forApp(txOp)
                val candidates = fetch(mc, afterId)
                if (candidates.isEmpty()) {
                    EMPTY_BATCH
                } else {
                    BatchResult(lastId = candidates.last(), deleted = deleteCustomers(mc, appId, candidates))
                }
            }
            if (batch === EMPTY_BATCH) break
            deleted += batch.deleted
            afterId = batch.lastId
        }
        return deleted
    }

    /**
     * 逐个二次校验（hasValidToken / hasActiveSubscription），对应删除的批量删其资源 + customer。
     * 返回本批实际删除的 customer 数。
     */
    private fun deleteCustomers(mc: ModuleCtx, appId: UUID, candidates: List<UUID>): Int {
        val toDelete = mutableListOf<UUID>()
        for (id in candidates) {
            val cust = customerRepo.findById(mc, appId, id) ?: continue
            val hasActive = subscriptionRepo.hasActiveByCustomer(mc, appId, id)
            if (hasActive) {
                log.warn("[anon-cleanup] app={} customer={} 命中 active 订阅，跳过删除（匿名却付费边界数据）", appId, id)
                continue
            }
            val hasToken = refreshTokenRepo.hasValidToken(mc, appId, id, ACTOR_TYPE)
            val state = AnonymousCleanupDecision.CustomerState(
                anonymous = cust.anonymous,
                merged = cust.mergedTo != null,
                tombstoneExpired = true, // 候选 SQL 已按 cutoff 过滤，tombstone 必超窗
                hasValidToken = hasToken,
                hasActiveSubscription = false,
            )
            if (AnonymousCleanupDecision.shouldDelete(state)) toDelete.add(id)
        }
        if (toDelete.isEmpty()) return 0

        // 先删资源（同事务）避免孤儿行，最后删 customer。
        val collectionIds = scanCollectionRepo.findIdsByCustomers(mc, appId, toDelete)
        scanCollectionItemRepo.physicalDeleteByCollections(mc, appId, collectionIds)
        scanCollectionRepo.physicalDeleteByCustomers(mc, appId, toDelete)
        scanRecordRepo.physicalDeleteByCustomers(mc, appId, toDelete)
        uploadRecordRepo.physicalDeleteByCustomers(mc, appId, toDelete)
        todoRepo.physicalDeleteByCustomers(mc, appId, toDelete)
        feedbackRepo.physicalDeleteByCustomers(mc, appId, toDelete)
        subscriptionRepo.physicalDeleteByCustomers(mc, appId, toDelete)

        val n = customerRepo.physicalDeleteByIds(mc, appId, toDelete)
        log.info("[anon-cleanup] app={} 删除 customer {} 个", appId, n)
        return n
    }

    /** 合成无请求的 OperationContext（后台任务）。isMutation=true → preferReader=false → writer。 */
    private fun newOpContext(appId: UUID?): OperationContext =
        OperationContext(
            req = RequestContext(appId = appId, actorType = ACTOR_TYPE),
            opName = "anonymousCleanup",
            isMutation = true,
        )
}

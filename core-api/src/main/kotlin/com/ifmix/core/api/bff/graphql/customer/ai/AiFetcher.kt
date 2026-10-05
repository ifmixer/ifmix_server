package com.ifmix.core.api.bff.graphql.customer.ai

import com.ifmix.core.api.generated.types.DeleteScanResult
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.generated.types.NewScanResult
import com.ifmix.core.api.generated.types.ScanStatus
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.generated.types.UpdateScanResult
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.jimmer.ActionContextHolder
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.entity.ai.AiTaskErrorCodes
import com.ifmix.core.api.entity.ai.DeepResearchStatuses
import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.ScanRecord
import com.ifmix.core.api.modules.ai.AiFacade
import com.ifmix.core.api.modules.ai.DeepResearchTaskService
import com.ifmix.core.api.modules.ai.ScanCollectionFacade
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import com.ifmix.core.api.generated.types.AddScanCollectionItemInput
import com.ifmix.core.api.generated.types.AddScanCollectionItemResult
import com.ifmix.core.api.generated.types.ListScanCollectionItemsInput
import com.ifmix.core.api.generated.types.RemoveScanCollectionItemsInput
import com.ifmix.core.api.generated.types.RemoveScanCollectionItemsResult
import com.ifmix.core.api.dto.ai.AddItemReq
import com.ifmix.core.api.dto.ai.ListItemsReq
import com.ifmix.core.api.dto.ai.RemoveItemsReq
import com.ifmix.core.api.entity.ai.ScanCollection
import com.ifmix.core.api.entity.ai.ScanCollectionItem
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.dataloader.MappedBatchLoader

@DgsComponent
class AiFetcher(
    private val aiService: AiFacade,
    private val collectionService: ScanCollectionFacade,
    private val deepResearchTaskService: DeepResearchTaskService,
    private val scanTaskService: com.ifmix.core.api.modules.ai.ScanTaskService,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: ActionContextProvider,
    private val rateLimiter: com.ifmix.core.api.infra.ratelimit.RateLimiter,
    private val rlProps: RateLimitProperties,
) {
    // --- Scan queries ---

    @DgsQuery(field = "q_ai_findMyScanById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): ScanRecord {
        val ctx = ctxProvider.fromDfe(dfe)
        return aiService.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "q_ai_findMyScans")
    fun findMyScans(
        dfe: DgsDataFetchingEnvironment,
        @InputArgument findOptions: com.ifmix.core.api.generated.types.CommonFindOptions?,
    ): Page<ScanRecord> {
        val ctx = ctxProvider.fromDfe(dfe)
        return aiService.findMyScans(ctx, findOptions)
    }

    // --- Collection queries ---

    @DgsQuery(field = "q_ai_getDefaultCollection")
    fun getDefault(dfe: DgsDataFetchingEnvironment): ScanCollection {
        val ctx = ctxProvider.fromDfe(dfe)
        return collectionService.getDefault(ctx)
    }

    @DgsQuery(field = "q_ai_findCollectionItemsByCursor")
    fun findItemsByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: ListScanCollectionItemsInput?): Page<ScanCollectionItem> {
        val ctx = ctxProvider.fromDfe(dfe)
        val req = input?.let { ListItemsReq(cursor = it.cursor, limit = it.limit, collectionId = null) }
        return collectionService.findItemsByCursor(ctx, req)
    }

    @DgsData(parentType = "ScanCollectionItem", field = "scanRecord")
    fun scanRecord(dfe: DgsDataFetchingEnvironment): CompletableFuture<ScanRecord> {
        // loader 的 key 是 ScanRecord.id —— 必须用 item.scanRecordId（item 自身 id 是独立 UUID，用它永远查不到）。
        val scanRecordId = dfe.getSource<ScanCollectionItem>()?.scanRecordId
            ?: throw ApiError(ErrorCode.NOT_FOUND, "ScanCollectionItem has no scanRecordId")
        val loader = dfe.getDataLoader<UUID, ScanRecord>(ScanRecordsDataLoader.NAME)
            ?: throw ApiError(ErrorCode.INTERNAL, "ScanRecordsDataLoader not registered")
        return loader.load(scanRecordId)
    }

    @DgsQuery(field = "q_ai_getDeepResearchStatus")
    fun getDeepResearchStatus(
        dfe: DgsDataFetchingEnvironment,
        @InputArgument deepResearchId: UUID,
    ): com.ifmix.core.api.generated.types.DeepResearchStatus {
        val ctx = ctxProvider.fromDfe(dfe)
        // 惰性超时判定可能产生 CAS 写（IN_PROGRESS → FAILED(TIMEOUT)），走事务
        val dr = globalTx.withTx(ctx) { txCtx -> aiService.getDeepResearchStatus(txCtx, deepResearchId) }
        return com.ifmix.core.api.generated.types.DeepResearchStatus(
            deepResearchId = dr.id,
            status = dr.status,
            errorCode = dr.errorCode,
        )
    }

    @DgsData(parentType = "ScanRecord", field = "latestDeepResearch")
    fun latestDeepResearch(dfe: DgsDataFetchingEnvironment): CompletableFuture<ScanDeepResearch?> {
        // 权威指针：按 scan_record.latest_deep_research_id 加载（不再按 scanRecordId 任意查）
        val drId = dfe.getSource<ScanRecord>()?.latestDeepResearchId
            ?: return CompletableFuture.completedFuture(null)
        val loader = dfe.getDataLoader<UUID, ScanDeepResearch>(LatestDeepResearchDataLoader.NAME)
            ?: throw ApiError(ErrorCode.INTERNAL, "LatestDeepResearchDataLoader not registered")
        @Suppress("UNCHECKED_CAST")
        return loader.load(drId) as CompletableFuture<ScanDeepResearch?>
    }

    // --- Scan mutations ---

    @DgsMutation(field = "m_ai_createScan")
    fun newScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: NewScanInput): NewScanResult {
        val ctx = ctxProvider.fromDfe(dfe)
        rateLimitByAction(ctx, "scan", DownstreamLimits.of(rlProps.scan), "too many createScan")
        val taskCtx = globalTx.withTx(ctx) { txCtx -> aiService.createScanTask(txCtx, input) }
        var status = com.ifmix.core.api.entity.ai.ScanStatuses.IN_PROGRESS
        var errorCode: String? = null
        try {
            scanTaskService.submit(taskCtx)
        } catch (e: Exception) {
            val casWon = globalTx.withTx(ctx) { txCtx ->
                aiService.casScanFailed(txCtx, taskCtx, AiTaskErrorCodes.TASK_SUBMISSION_FAILED, null)
            }
            if (casWon) {
                status = com.ifmix.core.api.entity.ai.ScanStatuses.FAILED
                errorCode = AiTaskErrorCodes.TASK_SUBMISSION_FAILED
            }
        }
        return NewScanResult(scanId = taskCtx.scanId, status = status, errorCode = errorCode)
    }

    @DgsQuery(field = "q_ai_getScanStatus")
    fun getScanStatus(dfe: DgsDataFetchingEnvironment, @InputArgument scanId: UUID): ScanStatus {
        val ctx = ctxProvider.fromDfe(dfe)
        val snapshot = globalTx.withTx(ctx) { txCtx -> aiService.getScanStatus(txCtx, scanId) }
        return ScanStatus(scanId = snapshot.scanId, status = snapshot.status, errorCode = snapshot.errorCode)
    }

    @DgsMutation(field = "m_ai_runDeepResearch")
    fun runDeepResearch(dfe: DgsDataFetchingEnvironment, @InputArgument input: com.ifmix.core.api.generated.types.RunDeepResearchInput): com.ifmix.core.api.generated.types.RunDeepResearchResult {
        // 异步化（设计 §3.3）：事务内完成 images 更新 + 配额预检 + 创建 IN_PROGRESS 记录，
        // 事务提交后（withTx 返回即已提交）才提交后台任务——后台才能读到已提交的记录。
        val ctx = ctxProvider.fromDfe(dfe)
        rateLimitByAction(ctx, "deep-research", DownstreamLimits.of(rlProps.deepResearch), "too many runDeepResearch")
        val taskCtx = globalTx.withTx(ctx) { txCtx -> aiService.createDeepResearchTask(txCtx, input) }
        // executor 提交失败（进程关闭/资源拒绝，设计 §9）：记录已 IN_PROGRESS → CAS 置 FAILED 并返回终态，
        // 前端拿到 deepResearchId + status=40 + errorCode 可直接显示失败/重试，不会拿不到 id 无法恢复。
        var status = DeepResearchStatuses.IN_PROGRESS
        var errorCode: String? = null
        try {
            deepResearchTaskService.submit(taskCtx)
        } catch (e: Exception) {
            val casWon = globalTx.withTx(ctx) { txCtx ->
                aiService.casDeepResearchFailed(txCtx, taskCtx.deepResearchId, AiTaskErrorCodes.TASK_SUBMISSION_FAILED, null)
            }
            if (casWon) {
                status = DeepResearchStatuses.FAILED
                errorCode = AiTaskErrorCodes.TASK_SUBMISSION_FAILED
            }
        }
        return com.ifmix.core.api.generated.types.RunDeepResearchResult(
            deepResearchId = taskCtx.deepResearchId,
            status = status,
            errorCode = errorCode,
        )
    }

    @DgsMutation(field = "m_ai_updateScan")
    fun updateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateScanInput): UpdateScanResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx ->
            val success = aiService.updateScan(txCtx, input)
            val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
                aiService.findById(txCtx, input.id)
            } else null
            UpdateScanResult(success = success, scanRecord = record)
        }
    }

    @DgsMutation(field = "m_ai_batchUpdateScan")
    fun batchUpdateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: com.ifmix.core.api.generated.types.BatchUpdateScanInput): com.ifmix.core.api.generated.types.BatchUpdateScanResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val updated = globalTx.withTx(ctx) { txCtx -> aiService.batchUpdateScan(txCtx, input) }
        return com.ifmix.core.api.generated.types.BatchUpdateScanResult(updatedCount = updated)
    }

    @DgsMutation(field = "m_ai_deleteScan")
    fun deleteScanById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteScanResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx -> DeleteScanResult(success = aiService.deleteScan(txCtx, id)) }
    }

    // --- Collection mutations ---

    @DgsMutation(field = "m_ai_addCollectionItem")
    fun addItem(dfe: DgsDataFetchingEnvironment, @InputArgument input: AddScanCollectionItemInput): AddScanCollectionItemResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx ->
            val result = collectionService.addItem(txCtx, AddItemReq(scanRecordId = input.scanRecordId))
            AddScanCollectionItemResult(collectionId = result.id, alreadyExists = false)
        }
    }

    @DgsMutation(field = "m_ai_removeCollectionItems")
    fun removeItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: RemoveScanCollectionItemsInput): RemoveScanCollectionItemsResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx ->
            val result = collectionService.removeItems(txCtx, RemoveItemsReq(scanRecordIds = input.scanRecordIds))
            RemoveScanCollectionItemsResult(removedCount = result.removed)
        }
    }

    /**
     * 下游接口的 install 层限流（attest 规格 §4.6「下游接口的 install 层」）：
     * - 有可信 [ActionContext.tokenInstallId]（只认 token 签名过的 iid；scan/DR 用 customer token 的 iid）：
     *   install 层（scan/DR：5/min + 100/天）→ IP 层（100/min + 1000/天）。install 层拒绝不碰 IP 计数器；
     *   IP 层拒绝时 install 额度已扣、**不退**（被拒请求一律不退款）。
     * - 无 iid（legacy fallback 兼容期，旧 app 只带可伪造的 x-install-id）：走 legacy IP 层
     *   （独立计数器，key 带 `legacy:` 段：scan 5/min+500/天、DR 3/min+300/天），不占新大额 IP 计数器——
     *   旧客户端的攻击面不被放大。legacy fallback 关闭后 `tokenInstallId==null` 的请求在业务前被拒
     *   （`mustGetTokenInstallId` 现有语义 401000，此处不重复）。
     * 顺序 install 层 → IP 层 → 业务；按 projectId 隔离。
     */
    /** scan / deep-research 的限流阈值（RateLimitProperties 的 Scan / DeepResearch 字段一致，取公共六项）。 */
    private data class DownstreamLimits(
        val legacyIpMinute: Int,
        val legacyIpDay: Int,
        val ipMinute: Int,
        val ipDay: Int,
        val installMinute: Int,
        val installDay: Int,
    ) {
        companion object {
            fun of(s: RateLimitProperties.Scan) = DownstreamLimits(s.legacyIpMinute, s.legacyIpDay, s.ipMinute, s.ipDay, s.installMinute, s.installDay)
            fun of(d: RateLimitProperties.DeepResearch) = DownstreamLimits(d.legacyIpMinute, d.legacyIpDay, d.ipMinute, d.ipDay, d.installMinute, d.installDay)
        }
    }

    private fun rateLimitByAction(
        ctx: com.ifmix.core.api.infra.http.ActionContext,
        action: String,
        limits: DownstreamLimits,
        message: String,
    ) {
        val projectId = ctx.mustGetProjectId()
        val ip = ctx.clientIp ?: "unknown"
        val iid = ctx.tokenInstallId
        when {
            iid != null -> {
                // install 层（防滥用，额度小）：先查 install 再查 IP（§4.6「被拒不退」语义）
                checkRateLimit(Window.MINUTE, "ratelimit:$projectId:$action:install:min:$iid", limits.installMinute, message)
                checkRateLimit(Window.UTC_DAY, "ratelimit:$projectId:$action:install:day:$iid", limits.installDay, message)
                // IP 层（系统防护，额度大）
                checkRateLimit(Window.MINUTE, "ratelimit:$projectId:$action:ip:min:$ip", limits.ipMinute, message)
                checkRateLimit(Window.UTC_DAY, "ratelimit:$projectId:$action:ip:day:$ip", limits.ipDay, message)
            }
            else -> {
                // legacy（无可信 iid，兼容期旧客户端）：严格阈值独立计数器，不占新大额 IP 计数器
                checkRateLimit(Window.MINUTE, "ratelimit:$projectId:$action:legacy:ip:min:$ip", limits.legacyIpMinute, message)
                checkRateLimit(Window.UTC_DAY, "ratelimit:$projectId:$action:legacy:ip:day:$ip", limits.legacyIpDay, message)
            }
        }
    }

    private fun checkRateLimit(window: Window, key: String, limit: Int, message: String) {
        when (val rl = rateLimiter.check(window, key, limit)) {
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, message, retryAfterSec = rl.retryAfterSec)
            else -> Unit
        }
    }
}

/** DataLoader for ScanRecord batch loading — caching=false prevents dirty reads across mutations. */
@DgsDataLoader(name = ScanRecordsDataLoader.NAME, caching = false)
class ScanRecordsDataLoader(
    private val scanRecordRepo: ScanRecordRepository,
    private val mcFactory: ModuleCtxFactory,
) : MappedBatchLoader<UUID, ScanRecord?> {
    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, ScanRecord?>> {
        val actionCtx = ActionContextHolder.current()
        val mc = mcFactory.forProject(actionCtx)
        val projectId = actionCtx.mustGetProjectId()
        // owner-scoped：只返回当前 customer 名下的记录，非本人的 id 一律 null。
        val customerId = actionCtx.mustGetActorId()
        val records = scanRecordRepo.findByIdsListView(mc, projectId, customerId, ids)
        val map = records.associateBy { it.id }
        return CompletableFuture.completedFuture(ids.associateWith { map[it] })
    }

    companion object {
        const val NAME = "scanRecords"
    }
}

/** DataLoader for the authoritative latest DeepResearch, keyed by scan_record.latest_deep_research_id. */
@DgsDataLoader(name = LatestDeepResearchDataLoader.NAME, caching = false)
class LatestDeepResearchDataLoader(
    private val aiService: AiFacade,
) : MappedBatchLoader<UUID, ScanDeepResearch?> {
    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, ScanDeepResearch?>> {
        val actionCtx = ActionContextHolder.current()
        val rows = aiService.findDeepResearchByIds(actionCtx, ids)
        val map = rows.associateBy { it.id }
        return CompletableFuture.completedFuture(ids.associateWith { map[it] })
    }

    companion object {
        const val NAME = "latestDeepResearch"
    }
}

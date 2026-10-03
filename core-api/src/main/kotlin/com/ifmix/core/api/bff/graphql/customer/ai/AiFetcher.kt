package com.ifmix.core.api.bff.graphql.customer.ai

import com.ifmix.core.api.generated.types.DeleteScanResult
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.generated.types.NewScanResult
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.generated.types.UpdateScanResult
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.jimmer.ActionContextHolder
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.entity.ai.DeepResearchErrorCodes
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
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: ActionContextProvider,
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
        @Suppress("UNCHECKED_CAST")
        val scanStatus = dr.errorDetails?.get("scan_status") as? Map<String, Any?>
        return com.ifmix.core.api.generated.types.DeepResearchStatus(
            deepResearchId = dr.id,
            status = dr.status,
            errorCode = dr.errorCode,
            scanStatus = scanStatus,
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
        // Step 1: AI 调用在事务外
        val aiResult = aiService.runAiScan(ctx, input)
        // Step 2: DB 写入在事务内
        val record = globalTx.withTx(ctx) { txCtx -> aiService.saveScanRecord(txCtx, aiResult) }
        return NewScanResult(scanRecord = record)
    }

    @DgsMutation(field = "m_ai_runDeepResearch")
    fun runDeepResearch(dfe: DgsDataFetchingEnvironment, @InputArgument input: com.ifmix.core.api.generated.types.RunDeepResearchInput): com.ifmix.core.api.generated.types.RunDeepResearchResult {
        // 异步化（设计 §3.3）：事务内完成 images 更新 + 配额预检 + 创建 IN_PROGRESS 记录，
        // 事务提交后（withTx 返回即已提交）才提交后台任务——后台才能读到已提交的记录。
        val ctx = ctxProvider.fromDfe(dfe)
        val taskCtx = globalTx.withTx(ctx) { txCtx -> aiService.createDeepResearchTask(txCtx, input) }
        // executor 提交失败（进程关闭/资源拒绝，设计 §9）：记录已 IN_PROGRESS → CAS 置 FAILED 并返回终态，
        // 前端拿到 deepResearchId + status=40 可直接显示失败/重试，不会拿不到 id 无法恢复。
        val status = try {
            deepResearchTaskService.submit(taskCtx)
            DeepResearchStatuses.IN_PROGRESS
        } catch (e: Exception) {
            val casWon = globalTx.withTx(ctx) { txCtx ->
                aiService.casDeepResearchFailed(txCtx, taskCtx.deepResearchId, DeepResearchErrorCodes.TASK_SUBMISSION_FAILED, null)
            }
            if (casWon) DeepResearchStatuses.FAILED else DeepResearchStatuses.IN_PROGRESS
        }
        return com.ifmix.core.api.generated.types.RunDeepResearchResult(
            deepResearchId = taskCtx.deepResearchId,
            status = status,
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

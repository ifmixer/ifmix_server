package com.ifmix.api.core.bff.graphql.customer.ai

import com.ifmix.api.core.generated.types.DeleteScanResult
import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.NewScanResult
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.UpdateScanResult
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jimmer.OperationContextHolder
import com.ifmix.api.core.infra.tx.GlobalTxRunner
import com.ifmix.api.core.entity.ai.ScanRecord
import com.ifmix.api.core.modules.ai.AiFacade
import com.ifmix.api.core.modules.ai.ScanCollectionFacade
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import com.ifmix.api.core.generated.types.AddScanCollectionItemInput
import com.ifmix.api.core.generated.types.AddScanCollectionItemResult
import com.ifmix.api.core.generated.types.ListScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsResult
import com.ifmix.api.core.dto.ai.AddItemReq
import com.ifmix.api.core.dto.ai.ListItemsReq
import com.ifmix.api.core.dto.ai.RemoveItemsReq
import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.entity.ai.ScanCollectionItem
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
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
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
        @InputArgument findOptions: com.ifmix.api.core.generated.types.CommonFindOptions?,
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
        val itemId = dfe.getSource<ScanCollectionItem>()?.id
            ?: throw ApiError(ErrorCode.NOT_FOUND, "ScanCollectionItem has no id")
        val loader = dfe.getDataLoader<UUID, ScanRecord>(ScanRecordsDataLoader.NAME)
            ?: throw ApiError(ErrorCode.INTERNAL, "ScanRecordsDataLoader not registered")
        return loader.load(itemId)
    }

    @DgsData(parentType = "ScanRecord", field = "deepResearch")
    fun deepResearch(dfe: DgsDataFetchingEnvironment): CompletableFuture<com.ifmix.api.core.entity.ai.ScanDeepResearch> {
        val scanRecordId = dfe.getSource<ScanRecord>()?.id
            ?: throw ApiError(ErrorCode.NOT_FOUND, "ScanRecord has no id")
        val loader = dfe.getDataLoader<UUID, com.ifmix.api.core.entity.ai.ScanDeepResearch>(DeepResearchDataLoader.NAME)
            ?: throw ApiError(ErrorCode.INTERNAL, "DeepResearchDataLoader not registered")
        return loader.load(scanRecordId)
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
    fun runDeepResearch(dfe: DgsDataFetchingEnvironment, @InputArgument input: com.ifmix.api.core.generated.types.RunDeepResearchInput): com.ifmix.api.core.generated.types.RunDeepResearchResult {
        val ctx = ctxProvider.fromDfe(dfe)
        // Step 1: 先更新图片（独立事务）— 即使后续 AI 失败，图片也已提交
        globalTx.withTx(ctx) { txCtx -> aiService.updateDeepResearchImages(txCtx, input) }
        // Step 2: AI 调用在事务外
        val result = aiService.runDeepResearch(ctx, input)
        // Step 3: 结果写入在事务内
        val success = globalTx.withTx(ctx) { txCtx -> aiService.saveDeepResearch(txCtx, result) }
        val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
            aiService.findById(ctx, input.scanRecordId)
        } else null
        return com.ifmix.api.core.generated.types.RunDeepResearchResult(success = success, scanRecord = record)
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
        val opCtx = OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()
        val records = scanRecordRepo.findByIdsListView(mc, appId, ids)
        val map = records.associateBy { it.id }
        return CompletableFuture.completedFuture(ids.associateWith { map[it] })
    }

    companion object {
        const val NAME = "scanRecords"
    }
}

/** DataLoader for ScanDeepResearch batch loading by scanRecordId. */
@DgsDataLoader(name = DeepResearchDataLoader.NAME, caching = false)
class DeepResearchDataLoader(
    private val aiService: AiFacade,
) : MappedBatchLoader<UUID, com.ifmix.api.core.entity.ai.ScanDeepResearch?> {
    override fun load(scanRecordIds: Set<UUID>): CompletionStage<Map<UUID, com.ifmix.api.core.entity.ai.ScanDeepResearch?>> {
        val opCtx = OperationContextHolder.current()
        val rows = aiService.findDeepResearchByScanRecordIds(opCtx, scanRecordIds)
        val map = rows.associateBy { it.scanRecordId }
        return CompletableFuture.completedFuture(scanRecordIds.associateWith { map[it] })
    }

    companion object {
        const val NAME = "scanDeepResearch"
    }
}

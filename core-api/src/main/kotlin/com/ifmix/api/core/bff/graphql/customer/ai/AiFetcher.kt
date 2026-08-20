package com.ifmix.api.core.bff.graphql.customer.ai

import com.ifmix.api.core.generated.types.DeleteScanPayload
import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.NewScanPayload
import com.ifmix.api.core.generated.types.ScanQueryInput
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.UpdateScanPayload
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
import com.ifmix.api.core.generated.types.AddScanCollectionItemPayload
import com.ifmix.api.core.generated.types.ListScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsPayload
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

@DgsComponent
class AiFetcher(
    private val aiService: AiFacade,
    private val collectionService: ScanCollectionFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
) {
    // --- Scan queries ---

    @DgsQuery(field = "query_ai_findScanById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): ScanRecord {
        val ctx = ctxProvider.fromDfe(dfe)
        return aiService.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "query_ai_findScansByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: ScanQueryInput?): Page<ScanRecord> {
        val ctx = ctxProvider.fromDfe(dfe)
        val q = input ?: ScanQueryInput()
        return aiService.findByCursorFiltered(ctx, q.cursor, q.limit, q.collected)
    }

    @DgsQuery(field = "query_ai_findScans")
    fun findScans(
        dfe: DgsDataFetchingEnvironment,
        @InputArgument filter: FilterGroup?,
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
    ): Page<ScanRecord> {
        val ctx = ctxProvider.fromDfe(dfe)
        return aiService.findByFilter(ctx, filter, cursor, limit)
    }

    // --- Collection queries ---

    @DgsQuery(field = "query_ai_getDefaultCollection")
    fun getDefault(dfe: DgsDataFetchingEnvironment): ScanCollection {
        val ctx = ctxProvider.fromDfe(dfe)
        return collectionService.getDefault(ctx)
    }

    @DgsQuery(field = "query_ai_findCollectionItemsByCursor")
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

    // --- Scan mutations ---

    @DgsMutation(field = "mutation_ai_createScan")
    fun newScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: NewScanInput): NewScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        // Step 1: AI 调用在事务外（耗时操作，不应占用 DB 连接）
        val aiResult = aiService.runAiScan(ctx, input)
        // Step 2: DB 写入在事务内
        val record = globalTx.withTx(ctx) { txCtx -> aiService.saveScanRecord(txCtx, aiResult) }
        return NewScanPayload(scanRecord = record)
    }

    @DgsMutation(field = "mutation_ai_updateScan")
    fun updateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateScanInput): UpdateScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx ->
            val success = aiService.updateScan(txCtx, input)
            val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
                aiService.findById(txCtx, input.id)
            } else null
            UpdateScanPayload(success = success, scanRecord = record)
        }
    }

    @DgsMutation(field = "mutation_ai_deleteScan")
    fun deleteScanById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx -> DeleteScanPayload(success = aiService.deleteScan(txCtx, id)) }
    }

    // --- Collection mutations ---

    @DgsMutation(field = "mutation_ai_addCollectionItem")
    fun addItem(dfe: DgsDataFetchingEnvironment, @InputArgument input: AddScanCollectionItemInput): AddScanCollectionItemPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx ->
            val result = collectionService.addItem(txCtx, AddItemReq(scanRecordId = input.scanRecordId))
            AddScanCollectionItemPayload(collectionId = result.id, alreadyExists = false)
        }
    }

    @DgsMutation(field = "mutation_ai_removeCollectionItems")
    fun removeItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: RemoveScanCollectionItemsInput): RemoveScanCollectionItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(ctx) { txCtx ->
            val result = collectionService.removeItems(txCtx, RemoveItemsReq(scanRecordIds = input.scanRecordIds))
            RemoveScanCollectionItemsPayload(removedCount = result.removed)
        }
    }
}

/** DataLoader for ScanRecord batch loading — caching=false prevents dirty reads across mutations. */
@DgsDataLoader(name = ScanRecordsDataLoader.NAME, caching = false)
class ScanRecordsDataLoader(
    private val scanRecordRepo: ScanRecordRepository,
    private val mcFactory: ModuleCtxFactory,
) {
    fun load(ids: Set<UUID>): CompletableFuture<Map<UUID, ScanRecord?>> {
        val opCtx = OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()
        val records = scanRecordRepo.findByIds(mc, appId, ids)
        val map = records.associateBy { it.id }
        return CompletableFuture.completedFuture(ids.associateWith { map[it] })
    }

    companion object {
        const val NAME = "scanRecords"
    }
}

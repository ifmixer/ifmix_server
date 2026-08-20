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
import org.dataloader.MappedBatchLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@DgsComponent
class AiFetcher(
    private val aiService: AiFacade,
    private val collectionService: ScanCollectionFacade,
    private val ctxProvider: OperationContextProvider,
) {
    // --- Scan queries ---

    @DgsQuery(field = "q_ai_findScanById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): ScanRecord {
        val ctx = ctxProvider.fromDfe(dfe)
        return aiService.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "q_ai_findScansByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: ScanQueryInput?): Page<ScanRecord> {
        val ctx = ctxProvider.fromDfe(dfe)
        val q = input ?: ScanQueryInput()
        return aiService.findByCursorFiltered(ctx, q.cursor, q.limit, q.collected)
    }

    @DgsQuery(field = "q_ai_findScans")
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
        val itemId = dfe.getSource<ScanCollectionItem>().id
        // Load via DataLoader
        return ScanRecordsDataLoader.load(itemId)
    }

    // --- Scan mutations ---

    @DgsMutation(field = "m_ai_createScan")
    fun newScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: NewScanInput): NewScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return NewScanPayload(scanRecord = aiService.newScan(ctx, input))
    }

    @DgsMutation(field = "m_ai_updateScan")
    fun updateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateScanInput): UpdateScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = aiService.updateScan(ctx, input)
        val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
            aiService.findById(ctx, input.id)
        } else null
        return UpdateScanPayload(success = success, scanRecord = record)
    }

    @DgsMutation(field = "m_ai_deleteScan")
    fun deleteScanById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = aiService.deleteScan(ctx, id)
        return DeleteScanPayload(success = success)
    }

    // --- Collection mutations ---

    @DgsMutation(field = "m_ai_addCollectionItem")
    fun addItem(dfe: DgsDataFetchingEnvironment, @InputArgument input: AddScanCollectionItemInput): AddScanCollectionItemPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val result = collectionService.addItem(ctx, AddItemReq(scanRecordId = input.scanRecordId))
        return AddScanCollectionItemPayload(collectionId = result.id, alreadyExists = false)
    }

    @DgsMutation(field = "m_ai_removeCollectionItems")
    fun removeItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: RemoveScanCollectionItemsInput): RemoveScanCollectionItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val result = collectionService.removeItems(ctx, RemoveItemsReq(scanRecordIds = input.scanRecordIds))
        return RemoveScanCollectionItemsPayload(removedCount = result.removed)
    }
}

/** DataLoader for ScanRecord batch loading — caching=false prevents dirty reads across mutations. */
@DgsDataLoader(name = ScanRecordsDataLoader.NAME, caching = false)
class ScanRecordsDataLoader(
    private val scanRecordRepo: ScanRecordRepository,
    private val mcFactory: ModuleCtxFactory,
) : MappedBatchLoader<UUID, ScanRecord?> {
    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, ScanRecord?>> {
        val opCtx = com.ifmix.api.core.infra.jimmer.OperationContextHolder.current()
        val mc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()
        val records = ids.map { id -> scanRecordRepo.findById(mc, appId, id) }
        return CompletableFuture.completedFuture(ids.associateWith { id -> records[ids.indexOf(id)] })
    }

    companion object {
        const val NAME = "scanRecords"

        fun load(id: UUID): CompletableFuture<ScanRecord?> {
            // Single-item load convenience — callers use the DataLoader directly via DGS
            throw UnsupportedOperationException("Use DataLoader registration, not direct load")
        }
    }
}

package com.ifmix.api.core.bff.graphql.customer.collection

import com.ifmix.api.core.generated.types.AddScanCollectionItemInput
import com.ifmix.api.core.generated.types.AddScanCollectionItemPayload
import com.ifmix.api.core.generated.types.ListScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsPayload
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jimmer.OperationContextHolder
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.entity.ai.ScanCollectionItem
import com.ifmix.api.core.entity.ai.ScanRecord
import com.ifmix.api.core.dto.ai.AddItemReq
import com.ifmix.api.core.dto.ai.ListItemsReq
import com.ifmix.api.core.dto.ai.RemoveItemsReq
import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import com.ifmix.api.core.modules.ai.ScanCollectionFacade
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
class CollectionFetcher(
    private val collectionService: ScanCollectionFacade,
    private val scanRecordRepo: ScanRecordRepository,
    private val ctxProvider: OperationContextProvider,
) {

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
        throw ApiError(ErrorCode.NOT_FOUND, "not implemented")
    }

    @DgsMutation(field = "mutation_ai_addCollectionItem")
    fun addItem(dfe: DgsDataFetchingEnvironment, @InputArgument input: AddScanCollectionItemInput): AddScanCollectionItemPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val restResult = collectionService.addItem(ctx, AddItemReq(scanRecordId = input.scanRecordId))
        return AddScanCollectionItemPayload(collectionId = restResult.id, alreadyExists = false)
    }

    @DgsMutation(field = "mutation_ai_removeCollectionItems")
    fun removeItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: RemoveScanCollectionItemsInput): RemoveScanCollectionItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val restResult = collectionService.removeItems(ctx, RemoveItemsReq(scanRecordIds = input.scanRecordIds))
        return RemoveScanCollectionItemsPayload(removedCount = restResult.removed)
    }
}

@DgsDataLoader(name = ScanRecordsDataLoader.NAME, caching = false)
class ScanRecordsDataLoader(
    private val scanRecordRepo: ScanRecordRepository,
    private val mcFactory: ModuleCtxFactory,
) : MappedBatchLoader<UUID, List<ScanRecord>> {
    override fun load(scanRecordIds: Set<UUID>): CompletionStage<Map<UUID, List<ScanRecord>>> {
        val opCtx = OperationContextHolder.current()
        val sc = mcFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()
        val records = scanRecordIds.map { id -> scanRecordRepo.findById(sc, appId, id) }
        val grouped = records.filterNotNull().groupBy { it.id }
        val result = scanRecordIds.associateWith { grouped[it] ?: emptyList() }
        return CompletableFuture.completedFuture(result)
    }
    companion object { const val NAME = "scanRecords" }
}
